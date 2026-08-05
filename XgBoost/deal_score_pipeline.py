"""Enterprise CRM Deal-Scoring pipeline (XGBoost regression).

End-to-end, production-oriented pipeline that predicts ``deal_score`` from CRM
engagement, qualification and risk signals.

Pipeline stages
---------------
1.  Load + profile the raw CSV.
2.  Clean (duplicates, missing values, IQR outlier audit).
3.  Feature engineering (ordinal encoding, multi-label objections, one-hot,
    MinMax scaling of ``engagement_score``).
4.  Exploratory data analysis (correlation matrix / heatmap, distributions).
5.  Feature selection (correlation, mutual information, XGBoost gain).
6.  Train / test split (80 / 20, ``random_state=42``).
7.  Model training (``RandomizedSearchCV``, 5-fold CV, then early stopping).
8.  Evaluation - MAE, MSE, RMSE, MAPE, sMAPE, R2 and adjusted R2 reported for
    the training *and* test splits side by side, plus cross-validated R2 and
    the train/test gap.
9.  Explainability (gain importance + SHAP summary).
10. Artefact persistence.
11. Single-record inference demo.

Design note
-----------
Category vocabularies are **detected from the CSV at runtime** rather than
hard-coded.  ``ORDINAL_LEXICON`` supplies the *semantic ordering* for every
label the project has ever used; only the labels actually present in the data
are encoded.  Unknown labels are reported loudly instead of silently mapped,
which keeps the pipeline safe when the CRM export schema drifts.

Usage
-----
Train the full pipeline on the source dataset::

    python deal_score_pipeline.py
    python deal_score_pipeline.py --csv data/my_training_data.csv --n-iter 100

Score *your own* CSV with the already-trained model (no retraining).  Include a
``deal_score`` column to get a full performance report; omit it for blind
predictions.  Either way ``outputs/scored_predictions.csv`` is written::

    python deal_score_pipeline.py --score-csv data/new_deals.csv
    python deal_score_pipeline.py --score-csv data/new_deals.csv --retrain
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import re
import sys
import warnings
from collections.abc import Sequence
from datetime import datetime, timezone
from pathlib import Path

import joblib
import matplotlib
import numpy as np
import pandas as pd

matplotlib.use("Agg")  # Headless-safe: render to files, never to a display.

# Imports below intentionally follow the backend selection above: importing
# pyplot binds the backend, so `matplotlib.use()` must run first.
import matplotlib.pyplot as plt
from sklearn.feature_selection import mutual_info_regression
from sklearn.metrics import (
    mean_absolute_error,
    mean_squared_error,
    r2_score,
)
from sklearn.model_selection import (
    KFold,
    RandomizedSearchCV,
    cross_val_score,
    train_test_split,
)
from sklearn.preprocessing import MinMaxScaler
from xgboost import XGBRegressor

warnings.filterwarnings("ignore", category=FutureWarning)
warnings.filterwarnings("ignore", category=UserWarning)

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #

RANDOM_STATE = 42
TEST_SIZE = 0.20
CV_FOLDS = 5
TOP_N_FEATURES = 15

#: Parallelism strategy.  XGBoost 3.3 on Python 3.14 crashes natively
#: ("access violation" in ``_ProxyDMatrixCreate``) when a booster is built
#: inside a joblib worker *process*.  So cross-validation runs sequentially
#: (``SEARCH_N_JOBS = 1``) and the cores are handed to the booster instead
#: (``MODEL_N_JOBS = -1``).  Total wall-time is comparable; stability is not.
SEARCH_N_JOBS = 1
MODEL_N_JOBS = -1

#: Bumped whenever the feature-engineering contract changes in a way that makes
#: older bundles incompatible.  ``load_bundle`` refuses to serve a mismatch.
PIPELINE_VERSION = "1.0.0"

TARGET = "deal_score"

#: Columns encoded ordinally.  Values are ordered low -> high on the business
#: axis the column measures (worse/less -> better/more).
ORDINAL_COLUMNS: tuple[str, ...] = (
    "customer_sentiment",
    "buying_intent",
    "relationship_strength",
    "budget_status",
    "decision_maker_involvement",
    "customer_urgency",
    "product_interest_level",
    "meeting_outcome",
    "competitor_mention",
    "implementation_readiness",
    "upsell_opportunity",
)

#: Columns expanded with one-hot encoding.
ONEHOT_COLUMNS: tuple[str, ...] = ("customer_requirements", "risk_factors")

#: Free-text multi-label column -> binary indicator columns + a count column.
MULTILABEL_COLUMN = "main_objections"

#: Continuous column normalised with MinMaxScaler.
SCALED_COLUMNS: tuple[str, ...] = ("engagement_score",)

#: Tokens in ``main_objections`` meaning "no objection recorded".
NULL_OBJECTION_TOKENS = frozenset({"none", "no objections", "no objection", "na", "n/a", "-"})

#: Explicit missing-value vocabulary for ``read_csv``.  Mirrors the pandas
#: default list *minus* ``"None"``, which this dataset uses as a real category.
NA_VALUES: tuple[str, ...] = (
    "", "#N/A", "#N/A N/A", "#NA", "-1.#IND", "-1.#QNAN", "-NaN", "-nan",
    "1.#IND", "1.#QNAN", "<NA>", "N/A", "NA", "NULL", "NaN", "n/a", "nan", "null",
)

#: Semantic rank for every ordinal label the CRM export has used.  Covers both
#: the current vocabulary and legacy/spec vocabularies, so a schema change on
#: either side still encodes with the correct ordering.
ORDINAL_LEXICON: dict[str, dict[str, int]] = {
    "customer_sentiment": {"negative": 0, "neutral": 1, "positive": 2},
    "buying_intent": {"low": 1, "medium": 2, "high": 3, "very high": 4},
    "relationship_strength": {"weak": 1, "moderate": 2, "medium": 2, "strong": 3, "very strong": 4},
    "budget_status": {
        "not available": 0,
        "not allocated": 0,
        "none": 0,
        "pending": 1,
        "under review": 1,
        "partially approved": 2,
        "partial": 2,
        "confirmed": 3,
        "fully approved": 3,
        "approved": 3,
    },
    "decision_maker_involvement": {
        "absent": 0,
        "no": 0,
        "none": 0,
        "partial": 1,
        "indirect": 1,
        "present": 2,
        "yes": 2,
        "direct": 2,
    },
    "customer_urgency": {"low": 1, "medium": 2, "high": 3, "critical": 4},
    "product_interest_level": {"low": 1, "medium": 2, "high": 3, "very high": 4},
    "meeting_outcome": {
        "no show / cancelled": 0,
        "no show": 0,
        "cancelled": 0,
        "negative": 0,
        "rescheduled": 1,
        "neutral": 1,
        "discussed requirements": 2,
        "positive": 2,
        "proposal sent": 3,
        "verbal agreement": 4,
    },
    "competitor_mention": {"no": 0, "none": 0, "yes": 1},
    "implementation_readiness": {
        "not ready": 0,
        "partial": 1,
        "partially ready": 1,
        "ready": 2,
        "fully ready": 3,
    },
    "upsell_opportunity": {"no": 0, "none": 0, "low": 1, "yes": 1, "medium": 2, "high": 3},
}

#: RandomizedSearchCV search space.
PARAM_DISTRIBUTIONS: dict[str, Sequence] = {
    "n_estimators": [200, 300, 400, 600, 800, 1000, 1500],
    "max_depth": [2, 3, 4, 5, 6, 8, 10],
    "learning_rate": [0.01, 0.02, 0.03, 0.05, 0.08, 0.1, 0.15, 0.2],
    "subsample": [0.6, 0.7, 0.8, 0.9, 1.0],
    "colsample_bytree": [0.6, 0.7, 0.8, 0.9, 1.0],
    "gamma": [0, 0.1, 0.3, 0.5, 1.0, 2.0, 5.0],
    "min_child_weight": [1, 2, 3, 5, 7, 10],
    "reg_alpha": [0, 0.001, 0.01, 0.1, 0.5, 1.0, 5.0],
    "reg_lambda": [0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 50.0],
}


# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #

def banner(title: str, char: str = "=") -> None:
    """Print a section header."""
    print(f"\n{char * 78}\n{title}\n{char * 78}")


def normalise_label(value: object) -> str:
    """Lower-case, trim and collapse whitespace so lookups are robust.

    ``'  Fully   Approved '`` and ``'fully approved'`` resolve identically.
    """
    text = str(value).strip().lower()
    text = re.sub(r"\s*/\s*", " / ", text)   # normalise spacing around slashes
    text = re.sub(r"\s+", " ", text)
    return text.rstrip(".")


def slugify(value: str) -> str:
    """Turn a free-text label into a safe snake_case column suffix."""
    text = normalise_label(value)
    text = re.sub(r"[^a-z0-9]+", "_", text)
    return text.strip("_")


def categorical_columns(frame: pd.DataFrame) -> list[str]:
    """Return non-numeric column names.

    Written against dtype *semantics* rather than a dtype identity check:
    pandas 3.0 stores text as the ``str`` dtype, so the older
    ``dtype == object`` test silently matches nothing.
    """
    return [
        c for c in frame.columns
        if not pd.api.types.is_numeric_dtype(frame[c])
        and not pd.api.types.is_bool_dtype(frame[c])
        and not pd.api.types.is_datetime64_any_dtype(frame[c])
    ]


# --------------------------------------------------------------------------- #
# STEP 1 - Load and profile
# --------------------------------------------------------------------------- #

def load_dataset(csv_path: Path) -> pd.DataFrame:
    """Load the CSV and print a full profiling report."""
    banner("STEP 1 | LOAD DATASET")

    if not csv_path.exists():
        raise FileNotFoundError(f"Dataset not found: {csv_path}")

    # `keep_default_na=False` is deliberate.  Pandas' default NA list contains
    # the literal string "None", which in this dataset is a *meaningful*
    # business label ("no objections raised") rather than a missing value.
    # Letting the default apply would null out those rows and imputation would
    # then invent an objection for customers who had none.  We therefore supply
    # an explicit NA vocabulary that omits "None".
    frame = pd.read_csv(csv_path, keep_default_na=False, na_values=NA_VALUES)
    # Normalise header casing so `lead_Score`/`lead_score` variants both work.
    frame.columns = [c.strip() for c in frame.columns]
    lowered = {c: c.lower() for c in frame.columns}
    if len(set(lowered.values())) == len(frame.columns):
        frame = frame.rename(columns=lowered)

    print(f"Source          : {csv_path}")
    print(f"Shape           : {frame.shape[0]} rows x {frame.shape[1]} columns")

    print("\n--- Data types ---")
    print(frame.dtypes.to_string())

    print("\n--- Null values per column ---")
    nulls = frame.isna().sum()
    print(nulls.to_string() if nulls.sum() else "No missing values detected.")

    dupes = int(frame.duplicated().sum())
    print(f"\n--- Duplicate rows ---\n{dupes}")

    print("\n--- Descriptive statistics (numeric) ---")
    print(frame.describe().T.to_string())

    cats = categorical_columns(frame)
    if cats:
        print("\n--- Descriptive statistics (categorical) ---")
        print(frame[cats].describe().T.to_string())

    if TARGET not in frame.columns:
        raise KeyError(f"Target column '{TARGET}' missing. Found: {list(frame.columns)}")

    print(f"\n--- Target distribution: {TARGET} ---")
    print(frame[TARGET].describe().to_string())
    print(f"skew     {frame[TARGET].skew():.4f}")
    print(f"kurtosis {frame[TARGET].kurtosis():.4f}")
    print(f"zeros    {int((frame[TARGET] == 0).sum())}")

    print("\n--- Unique values per categorical column ---")
    for col in cats:
        uniques = frame[col].dropna().unique()
        preview = list(uniques[:12])
        suffix = f" ... (+{len(uniques) - 12} more)" if len(uniques) > 12 else ""
        print(f"  {col:<30} n={len(uniques):<4} {preview}{suffix}")

    return frame


# --------------------------------------------------------------------------- #
# STEP 2 - Cleaning
# --------------------------------------------------------------------------- #

def clean_dataset(frame: pd.DataFrame) -> pd.DataFrame:
    """Drop duplicates, impute missing values, audit IQR outliers.

    Outlier policy: report every IQR violation, but only *remove* rows that sit
    beyond the far-outlier fence (Q1/Q3 -/+ 3 * IQR) and only while such rows
    stay under 5 % of the dataset.  This protects the target distribution from
    being reshaped by aggressive trimming.
    """
    banner("STEP 2 | DATA CLEANING")
    before = len(frame)

    # -- duplicates ---------------------------------------------------------
    frame = frame.drop_duplicates().reset_index(drop=True)
    print(f"Duplicates removed        : {before - len(frame)}  ({before} -> {len(frame)} rows)")

    # -- missing values -----------------------------------------------------
    missing = frame.isna().sum()
    missing = missing[missing > 0]
    if missing.empty:
        print("Missing values            : none")
    else:
        for col, count in missing.items():
            if pd.api.types.is_numeric_dtype(frame[col]):
                fill = frame[col].median()
                strategy = "median"
            else:
                mode = frame[col].mode(dropna=True)
                fill = mode.iloc[0] if not mode.empty else "Unknown"
                strategy = "mode"
            frame[col] = frame[col].fillna(fill)
            print(f"Imputed {col:<28} {count:>4} value(s) via {strategy} -> {fill!r}")

    # -- IQR outlier audit --------------------------------------------------
    numeric_cols = [c for c in frame.select_dtypes(include=np.number).columns if c != TARGET]
    print("\n--- IQR outlier audit (features only; target left untouched) ---")

    far_outlier_mask = pd.Series(False, index=frame.index)
    for col in numeric_cols:
        q1, q3 = frame[col].quantile([0.25, 0.75])
        iqr = q3 - q1
        if iqr == 0:
            print(f"  {col:<28} IQR=0 -> skipped")
            continue
        mild = ((frame[col] < q1 - 1.5 * iqr) | (frame[col] > q3 + 1.5 * iqr))
        far = ((frame[col] < q1 - 3.0 * iqr) | (frame[col] > q3 + 3.0 * iqr))
        far_outlier_mask |= far
        print(
            f"  {col:<28} Q1={q1:<8.2f} Q3={q3:<8.2f} IQR={iqr:<8.2f} "
            f"mild={int(mild.sum()):<4} far={int(far.sum())}"
        )

    n_far = int(far_outlier_mask.sum())
    share = n_far / len(frame) if len(frame) else 0.0
    if n_far == 0:
        print("\nNo far outliers found -> dataset retained in full.")
    elif share < 0.05:
        frame = frame.loc[~far_outlier_mask].reset_index(drop=True)
        print(f"\nRemoved {n_far} far-outlier row(s) ({share:.2%}) -> {len(frame)} rows remain.")
    else:
        print(
            f"\n{n_far} far-outlier rows ({share:.2%}) exceed the 5% safety "
            "threshold -> retained to preserve the distribution."
        )

    print(f"\nFinal cleaned shape       : {frame.shape}")
    return frame


# --------------------------------------------------------------------------- #
# STEP 3 - Feature engineering
# --------------------------------------------------------------------------- #

def build_ordinal_maps(frame: pd.DataFrame) -> dict[str, dict[str, int]]:
    """Derive ordinal maps from the categories actually present in the CSV.

    Only labels found in the data are mapped.  Ranks come from
    ``ORDINAL_LEXICON`` so the encoding carries real business ordering; labels
    outside the lexicon fall back to sorted order and are reported.
    """
    maps: dict[str, dict[str, int]] = {}

    for col in ORDINAL_COLUMNS:
        if col not in frame.columns:
            print(f"  [skip] {col} not present in dataset")
            continue
        if pd.api.types.is_numeric_dtype(frame[col]):
            print(f"  [skip] {col} already numeric -> left unencoded")
            continue

        present = sorted({normalise_label(v) for v in frame[col].dropna().unique()})
        lexicon = ORDINAL_LEXICON.get(col, {})
        known = [lbl for lbl in present if lbl in lexicon]
        unknown = [lbl for lbl in present if lbl not in lexicon]

        mapping = {lbl: lexicon[lbl] for lbl in known}
        if unknown:
            # Unknown labels are appended above the known range, never guessed
            # into the middle of an existing ordering.
            start = max(mapping.values(), default=-1) + 1
            for offset, lbl in enumerate(unknown):
                mapping[lbl] = start + offset
            print(f"  [WARN] {col}: labels absent from lexicon {unknown} -> appended as {start}+")

        maps[col] = mapping
        rendered = ", ".join(f"{k}={v}" for k, v in sorted(mapping.items(), key=lambda kv: kv[1]))
        print(f"  {col:<30} {rendered}")

    return maps


def discover_objection_tokens(series: pd.Series) -> list[str]:
    """Extract the atomic objection vocabulary from the multi-label column.

    Handles both ``;`` and ``,`` separators and drops "no objection" markers.
    """
    tokens: set[str] = set()
    for raw in series.dropna():
        for part in re.split(r"[;,]", str(raw)):
            label = normalise_label(part)
            if label and label not in NULL_OBJECTION_TOKENS:
                tokens.add(label)
    return sorted(tokens)


def engineer_features(
    frame: pd.DataFrame,
) -> tuple[pd.DataFrame, dict[str, dict[str, int]], list[str], list[str], MinMaxScaler]:
    """Apply the full feature-engineering contract.

    Returns the engineered frame plus every artefact inference needs:
    ordinal maps, objection vocabulary, one-hot column names and the scaler.
    """
    banner("STEP 3 | FEATURE ENGINEERING")
    data = frame.copy()

    # -- 3a. Ordinal encoding ----------------------------------------------
    print("--- Ordinal encoding (auto-detected categories) ---")
    ordinal_maps = build_ordinal_maps(data)
    for col, mapping in ordinal_maps.items():
        # `m=mapping` binds the loop variable by value - without it the lambda
        # would close over whatever `mapping` holds when it finally runs.
        data[col] = data[col].map(lambda v, m=mapping: m.get(normalise_label(v), np.nan))
        if data[col].isna().any():  # defensive: unseen label at transform time
            data[col] = data[col].fillna(int(np.median(list(mapping.values()))))
        data[col] = data[col].astype(int)

    # -- 3b. Multi-label objections ----------------------------------------
    print(f"\n--- Multi-label expansion: {MULTILABEL_COLUMN} ---")
    objection_tokens: list[str] = []
    if MULTILABEL_COLUMN in data.columns:
        objection_tokens = discover_objection_tokens(data[MULTILABEL_COLUMN])
        print(f"Detected {len(objection_tokens)} objection types: {objection_tokens}")

        parsed = data[MULTILABEL_COLUMN].fillna("").map(
            lambda raw: {
                normalise_label(p)
                for p in re.split(r"[;,]", str(raw))
                if normalise_label(p) and normalise_label(p) not in NULL_OBJECTION_TOKENS
            }
        )
        for token in objection_tokens:
            data[f"objection_{slugify(token)}"] = parsed.map(lambda s, t=token: int(t in s))
        data["num_objections"] = parsed.map(len).astype(int)

        print(f"Created {len(objection_tokens)} binary flags + 'num_objections'")
        print(f"num_objections distribution:\n{data['num_objections'].value_counts().sort_index().to_string()}")
        data = data.drop(columns=[MULTILABEL_COLUMN])
        print(f"Dropped raw column '{MULTILABEL_COLUMN}'")

    # -- 3c. One-hot encoding ----------------------------------------------
    print("\n--- One-hot encoding ---")
    onehot_present = [c for c in ONEHOT_COLUMNS if c in data.columns]
    for col in onehot_present:
        print(f"  {col:<28} categories={sorted(data[col].dropna().unique().tolist())}")
    if onehot_present:
        data = pd.get_dummies(data, columns=list(onehot_present), prefix=list(onehot_present))
        # get_dummies yields bools on modern pandas; XGBoost prefers ints.
        bool_cols = data.select_dtypes(include=bool).columns
        data[bool_cols] = data[bool_cols].astype(int)
    onehot_feature_names = [
        c for c in data.columns if any(c.startswith(f"{p}_") for p in onehot_present)
    ]
    print(f"  -> generated {len(onehot_feature_names)} dummy columns")

    # -- 3d. Scaling --------------------------------------------------------
    print("\n--- MinMax scaling ---")
    scaler = MinMaxScaler()
    scale_cols = [c for c in SCALED_COLUMNS if c in data.columns]
    if scale_cols:
        data[scale_cols] = scaler.fit_transform(data[scale_cols])
        print(f"  Scaled {scale_cols} to [0, 1]; ordinal features left unscaled.")
    else:
        scaler = None

    # -- residual object columns -------------------------------------------
    leftovers = categorical_columns(data)
    if leftovers:
        print(f"\n[WARN] Unencoded object columns remain and will be dropped: {leftovers}")
        data = data.drop(columns=leftovers)

    print(f"\nEngineered shape          : {data.shape}")
    return data, ordinal_maps, objection_tokens, onehot_feature_names, scaler


# --------------------------------------------------------------------------- #
# STEP 4 - Exploratory data analysis
# --------------------------------------------------------------------------- #

def run_eda(data: pd.DataFrame, outdir: Path) -> pd.DataFrame:
    """Correlation matrix / heatmap plus distribution plots."""
    banner("STEP 4 | EXPLORATORY DATA ANALYSIS")

    corr = data.corr(numeric_only=True)
    corr.to_csv(outdir / "correlation_matrix.csv")

    print(f"--- Correlation with {TARGET} (top 20 by |r|) ---")
    target_corr = corr[TARGET].drop(TARGET).sort_values(key=np.abs, ascending=False)
    print(target_corr.head(20).to_string())

    # Correlation heatmap (matplotlib only - no seaborn dependency).
    size = max(10, 0.42 * len(corr))
    fig, ax = plt.subplots(figsize=(size, size * 0.85))
    im = ax.imshow(corr.values, cmap="coolwarm", vmin=-1, vmax=1)
    ax.set_xticks(range(len(corr)), corr.columns, rotation=90, fontsize=7)
    ax.set_yticks(range(len(corr)), corr.index, fontsize=7)
    ax.set_title("Feature Correlation Heatmap", fontsize=13, pad=12)
    fig.colorbar(im, ax=ax, fraction=0.035, pad=0.02)
    fig.tight_layout()
    fig.savefig(outdir / "correlation_heatmap.png", dpi=150)
    plt.close(fig)

    # Feature distributions.
    features = [c for c in data.columns if c != TARGET]
    ncols = 5
    nrows = int(np.ceil(len(features) / ncols))
    fig, axes = plt.subplots(nrows, ncols, figsize=(4 * ncols, 2.9 * nrows))
    # strict=False is intended: the grid is padded to a full rectangle, so the
    # trailing unused axes are switched off in the loop below.
    for ax, col in zip(np.ravel(axes), features, strict=False):
        ax.hist(data[col], bins=min(20, max(3, data[col].nunique())), color="#4C78A8", edgecolor="white")
        ax.set_title(col, fontsize=9)
        ax.tick_params(labelsize=7)
    for ax in np.ravel(axes)[len(features):]:
        ax.axis("off")
    fig.suptitle("Feature Distributions", fontsize=14)
    fig.tight_layout(rect=(0, 0, 1, 0.98))
    fig.savefig(outdir / "feature_distributions.png", dpi=140)
    plt.close(fig)

    # Target distribution.
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 4.5))
    ax1.hist(data[TARGET], bins=30, color="#54A24B", edgecolor="white")
    ax1.set_title(f"{TARGET} - histogram")
    ax1.set_xlabel(TARGET)
    try:  # `vert` deprecated in Matplotlib 3.11 in favour of `orientation`
        ax2.boxplot(data[TARGET], orientation="horizontal", widths=0.6)
    except TypeError:
        ax2.boxplot(data[TARGET], vert=False, widths=0.6)
    ax2.set_title(f"{TARGET} - boxplot")
    fig.tight_layout()
    fig.savefig(outdir / "target_distribution.png", dpi=150)
    plt.close(fig)

    print("\nSaved: correlation_matrix.csv, correlation_heatmap.png, "
          "feature_distributions.png, target_distribution.png")
    return corr


# --------------------------------------------------------------------------- #
# STEP 5 - Feature selection
# --------------------------------------------------------------------------- #

def select_features(X: pd.DataFrame, y: pd.Series, corr: pd.DataFrame) -> pd.DataFrame:
    """Rank features by correlation, mutual information and XGBoost gain."""
    banner("STEP 5 | FEATURE SELECTION")

    abs_corr = corr[TARGET].drop(TARGET).abs()

    mi = mutual_info_regression(X, y, random_state=RANDOM_STATE)
    mi_series = pd.Series(mi, index=X.columns)

    probe = XGBRegressor(
        n_estimators=400,
        max_depth=4,
        learning_rate=0.05,
        subsample=0.9,
        colsample_bytree=0.9,
        random_state=RANDOM_STATE,
        n_jobs=MODEL_N_JOBS,
    )
    probe.fit(X, y)
    gain = pd.Series(probe.feature_importances_, index=X.columns)

    ranking = pd.DataFrame(
        {
            "abs_correlation": abs_corr.reindex(X.columns),
            "mutual_information": mi_series,
            "xgb_gain_importance": gain,
        }
    )
    # Average of per-metric ranks -> a single consensus ordering.
    ranking["consensus_rank"] = ranking.rank(ascending=False).mean(axis=1)
    ranking = ranking.sort_values("consensus_rank")

    print(f"--- Top {TOP_N_FEATURES} features (consensus of 3 methods) ---")
    print(ranking.head(TOP_N_FEATURES).to_string(float_format=lambda v: f"{v:.5f}"))

    weak = ranking[
        (ranking["abs_correlation"] < 0.02)
        & (ranking["mutual_information"] <= 0.0)
        & (ranking["xgb_gain_importance"] < 0.005)
    ]
    if not weak.empty:
        print(f"\nLow-signal candidates (kept; XGBoost regularisation handles them): {list(weak.index)}")

    return ranking


# --------------------------------------------------------------------------- #
# STEP 7 - Training
# --------------------------------------------------------------------------- #

def fit_with_early_stopping(
    params: dict,
    X_train: pd.DataFrame,
    y_train: pd.Series,
    X_val: pd.DataFrame,
    y_val: pd.Series,
    rounds: int = 50,
) -> XGBRegressor:
    """Fit an XGBRegressor with early stopping across XGBoost 1.x / 2.x / 3.x.

    XGBoost >= 2.0 takes ``early_stopping_rounds`` in the constructor; 1.x takes
    it in ``fit``.  Try the modern path first and fall back.
    """
    try:
        model = XGBRegressor(**params, early_stopping_rounds=rounds, eval_metric="rmse")
        model.fit(X_train, y_train, eval_set=[(X_val, y_val)], verbose=False)
        return model
    except TypeError:
        model = XGBRegressor(**params, eval_metric="rmse")
        model.fit(
            X_train,
            y_train,
            eval_set=[(X_val, y_val)],
            early_stopping_rounds=rounds,
            verbose=False,
        )
        return model


def train_model(
    X_train: pd.DataFrame, y_train: pd.Series, n_iter: int
) -> tuple[XGBRegressor, dict, RandomizedSearchCV]:
    """Randomised hyper-parameter search, then a refit with early stopping.

    Early stopping needs a validation set that the search itself never saw, so
    it is applied *after* the CV search on an internal hold-out carved from the
    training data.  This keeps the test set completely untouched.
    """
    banner("STEP 7 | MODEL TRAINING")

    # The booster takes the cores (MODEL_N_JOBS) while the search runs
    # sequentially (SEARCH_N_JOBS) - see the note beside those constants.
    base = XGBRegressor(
        objective="reg:squarederror",
        random_state=RANDOM_STATE,
        n_jobs=MODEL_N_JOBS,
        tree_method="hist",
    )

    search = RandomizedSearchCV(
        estimator=base,
        param_distributions=PARAM_DISTRIBUTIONS,
        n_iter=n_iter,
        scoring="neg_root_mean_squared_error",
        cv=KFold(n_splits=CV_FOLDS, shuffle=True, random_state=RANDOM_STATE),
        verbose=1,
        random_state=RANDOM_STATE,
        n_jobs=SEARCH_N_JOBS,
        refit=True,
        return_train_score=True,
    )

    print(f"RandomizedSearchCV: {n_iter} candidates x {CV_FOLDS} folds "
          f"= {n_iter * CV_FOLDS} fits")
    search.fit(X_train, y_train)

    print("\n--- Best hyper-parameters ---")
    for key, value in sorted(search.best_params_.items()):
        print(f"  {key:<20} {value}")
    print(f"\nBest CV RMSE : {-search.best_score_:.4f}")

    # -- early stopping to choose the boosting-round count ------------------
    #
    # Early stopping needs a validation split, but a model trained on the whole
    # training set would score that split in-sample.  So early stopping is used
    # only to *discover* the optimal round count on an honest internal split;
    # the final model is then refit on the full training set with that count.
    print("\n--- Early stopping: discovering the optimal boosting-round count ---")
    X_sub, X_val, y_sub, y_val = train_test_split(
        X_train, y_train, test_size=0.20, random_state=RANDOM_STATE
    )

    tuned = dict(search.best_params_)
    tuned.update(
        objective="reg:squarederror",
        random_state=RANDOM_STATE,
        n_jobs=MODEL_N_JOBS,
        tree_method="hist",
    )
    # Give early stopping headroom to find the optimum below the tuned ceiling.
    ceiling = max(int(tuned.get("n_estimators", 500)), 1500)
    tuned["n_estimators"] = ceiling

    probe = fit_with_early_stopping(tuned, X_sub, y_sub, X_val, y_val)
    best_iter = getattr(probe, "best_iteration", None)
    rounds = int(best_iter) + 1 if best_iter is not None else int(search.best_params_["n_estimators"])
    val_rmse = float(np.sqrt(mean_squared_error(y_val, probe.predict(X_val))))
    print(f"  Optimal rounds : {rounds} (ceiling {ceiling})")
    print(f"  Hold-out RMSE  : {val_rmse:.4f}  [honest - {len(X_val)} rows unseen during this fit]")

    # Refit on the full training set with the discovered round count.
    final_params = dict(tuned, n_estimators=rounds)
    candidate = XGBRegressor(**final_params)
    candidate.fit(X_train, y_train, verbose=False)

    # Choose between the two candidates using 5-fold CV on the training set -
    # the only comparison that is out-of-sample for *both* models.
    print("\n--- Candidate selection (5-fold CV on training data - unbiased) ---")
    folds = KFold(n_splits=CV_FOLDS, shuffle=True, random_state=RANDOM_STATE)

    cand_rmse = -cross_val_score(
        cv_safe_clone(candidate), X_train, y_train, cv=folds,
        scoring="neg_root_mean_squared_error", n_jobs=SEARCH_N_JOBS,
    ).mean()
    search_rmse = -search.best_score_

    print(f"  early-stopped refit (n_estimators={rounds:<5d}) CV RMSE = {cand_rmse:.4f}")
    print(f"  search refit        (n_estimators={search.best_params_['n_estimators']:<5d}) "
          f"CV RMSE = {search_rmse:.4f}")

    if cand_rmse < search_rmse:
        print("-> Early-stopped model retained.")
        return candidate, final_params, search

    print("-> Search refit retained.")
    return search.best_estimator_, search.best_params_, search


# --------------------------------------------------------------------------- #
# STEP 8 - Evaluation
# --------------------------------------------------------------------------- #

def safe_mape(y_true: np.ndarray, y_pred: np.ndarray) -> tuple[float, int]:
    """MAPE computed only over non-zero actuals (MAPE is undefined at zero).

    Returns ``(mape_percent, n_excluded)``.
    """
    y_true = np.asarray(y_true, dtype=float)
    y_pred = np.asarray(y_pred, dtype=float)
    mask = y_true != 0
    excluded = int((~mask).sum())
    if not mask.any():
        return float("nan"), excluded
    return float(np.mean(np.abs((y_true[mask] - y_pred[mask]) / y_true[mask])) * 100.0), excluded


def smape(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    """Symmetric MAPE - defined even when actuals hit zero."""
    y_true = np.asarray(y_true, dtype=float)
    y_pred = np.asarray(y_pred, dtype=float)
    denom = (np.abs(y_true) + np.abs(y_pred)) / 2.0
    ratio = np.divide(np.abs(y_true - y_pred), denom, out=np.zeros_like(denom), where=denom != 0)
    return float(np.mean(ratio) * 100.0)


def cv_safe_clone(model: XGBRegressor) -> XGBRegressor:
    """Return a clone that can be refit inside cross-validation.

    A model carrying ``early_stopping_rounds`` raises when refit without an
    ``eval_set``, which is exactly what ``cross_val_score`` does.  Strip the
    early-stopping settings and pin ``n_estimators`` to the round count early
    stopping actually chose, so CV evaluates the same effective model.
    """
    params = {
        k: v for k, v in model.get_params().items()
        if k not in ("early_stopping_rounds", "eval_metric", "callbacks")
    }
    best_iter = getattr(model, "best_iteration", None)
    if best_iter is not None:
        params["n_estimators"] = int(best_iter) + 1
    # Single-threaded: cross_val_score parallelises across folds, so a
    # multi-threaded booster inside each fold would nest and crash (see
    # the note in train_model).
    params["n_jobs"] = MODEL_N_JOBS
    return XGBRegressor(**params)


def metric_suite(
    y_true: pd.Series, y_pred: np.ndarray, n_features: int
) -> dict[str, float]:
    """Compute MAE / MSE / RMSE / MAPE / sMAPE / R2 / adjusted R2 for one split.

    Shared by the training and test evaluations so both are measured
    identically - the only honest way to compare them.
    """
    truth = y_true.to_numpy() if hasattr(y_true, "to_numpy") else np.asarray(y_true)
    mse = float(mean_squared_error(truth, y_pred))
    r2 = float(r2_score(truth, y_pred))
    mape, excluded = safe_mape(truth, y_pred)

    n = len(truth)
    adj_r2 = (
        1 - (1 - r2) * (n - 1) / (n - n_features - 1)
        if n > n_features + 1 else float("nan")
    )

    return {
        "mae": float(mean_absolute_error(truth, y_pred)),
        "mse": mse,
        "rmse": float(np.sqrt(mse)),
        "mape_percent": mape,
        "mape_excluded_zeros": excluded,
        "smape_percent": smape(truth, y_pred),
        "r2": r2,
        "adjusted_r2": adj_r2,
        "n_rows": n,
    }


def evaluate_model(
    model: XGBRegressor,
    X_train: pd.DataFrame,
    y_train: pd.Series,
    X_test: pd.DataFrame,
    y_test: pd.Series,
) -> dict[str, float]:
    """Compute the regression metric suite on BOTH the training and test sets.

    Training scores are reported alongside test scores so the generalisation
    gap is visible directly.  Training numbers are in-sample by construction
    and always flatter the model - only the test column estimates real
    performance.
    """
    banner("STEP 8 | MODEL EVALUATION")

    cv_model = cv_safe_clone(model)
    p = X_train.shape[1]

    train = metric_suite(y_train, model.predict(X_train), p)
    test = metric_suite(y_test, model.predict(X_test), p)

    # Aliases for the diagnostics below and the backwards-compatible keys.
    mae, mse, rmse = test["mae"], test["mse"], test["rmse"]
    mape, excluded = test["mape_percent"], test["mape_excluded_zeros"]
    r2, adj_r2 = test["r2"], test["adjusted_r2"]

    cv_r2 = cross_val_score(
        cv_model, X_train, y_train,
        cv=KFold(n_splits=CV_FOLDS, shuffle=True, random_state=RANDOM_STATE),
        scoring="r2", n_jobs=SEARCH_N_JOBS,
    )
    cv_rmse = -cross_val_score(
        cv_model, X_train, y_train,
        cv=KFold(n_splits=CV_FOLDS, shuffle=True, random_state=RANDOM_STATE),
        scoring="neg_root_mean_squared_error", n_jobs=SEARCH_N_JOBS,
    )

    train_r2 = train["r2"]
    gap = train_r2 - r2

    print(f"--- Metrics: TRAIN ({train['n_rows']} rows) vs TEST ({test['n_rows']} rows) ---")
    print(f"  {'Metric':<24}{'TRAIN':>14}{'TEST':>14}")
    for label, key, suffix in (
        ("MAE", "mae", ""),
        ("MSE", "mse", ""),
        ("RMSE", "rmse", ""),
        ("MAPE", "mape_percent", " %"),
        ("sMAPE (zero-safe)", "smape_percent", " %"),
        ("R2 Score", "r2", ""),
        ("Adjusted R2", "adjusted_r2", ""),
    ):
        print(f"  {label:<24}{train[key]:>14.4f}{test[key]:>14.4f}{suffix}")
    if excluded:
        print(f"  (MAPE excludes {excluded} zero-valued actual(s) - undefined at 0)")

    print("\n--- Cross-validation on training data (5-fold) ---")
    print(f"  CV R2   : {cv_r2.mean():.4f} +/- {cv_r2.std():.4f}   folds={np.round(cv_r2, 4).tolist()}")
    print(f"  CV RMSE : {cv_rmse.mean():.4f} +/- {cv_rmse.std():.4f}")

    print("\n--- Fit diagnostics ---")
    verdict = "healthy" if gap < 0.10 else ("mild overfit" if gap < 0.25 else "OVERFITTING")
    print(f"  Train R2 - Test R2 gap : {gap:.4f}  -> {verdict}")
    print("  NOTE: training scores are in-sample and always optimistic;")
    print("        only the TEST column estimates real-world performance.")

    return {
        # Test metrics keep their original unprefixed keys so existing
        # artefacts and downstream readers stay compatible.
        "mae": float(mae), "mse": float(mse), "rmse": rmse,
        "mape_percent": mape, "mape_excluded_zeros": excluded,
        "smape_percent": test["smape_percent"],
        "r2": float(r2), "adjusted_r2": float(adj_r2),
        "n_test_rows": int(test["n_rows"]),
        "train_mae": train["mae"], "train_mse": train["mse"],
        "train_rmse": train["rmse"], "train_mape_percent": train["mape_percent"],
        "train_smape_percent": train["smape_percent"],
        "train_r2": float(train_r2), "train_adjusted_r2": train["adjusted_r2"],
        "n_train_rows": int(train["n_rows"]),
        "cv_r2_mean": float(cv_r2.mean()), "cv_r2_std": float(cv_r2.std()),
        "cv_rmse_mean": float(cv_rmse.mean()), "cv_rmse_std": float(cv_rmse.std()),
        "train_test_r2_gap": float(gap),
    }


# --------------------------------------------------------------------------- #
# STEP 9 - Explainability
# --------------------------------------------------------------------------- #

def explain_model(
    model: XGBRegressor, X_train: pd.DataFrame, X_test: pd.DataFrame, outdir: Path
) -> pd.DataFrame:
    """Gain importance plot + SHAP summary plot."""
    banner("STEP 9 | EXPLAINABILITY")

    importance = (
        pd.DataFrame(
            {"feature": X_train.columns, "gain_importance": model.feature_importances_}
        )
        .sort_values("gain_importance", ascending=False)
        .reset_index(drop=True)
    )

    print(f"--- Top {TOP_N_FEATURES} features by XGBoost gain ---")
    print(importance.head(TOP_N_FEATURES).to_string(index=False, float_format=lambda v: f"{v:.5f}"))

    top = importance.head(TOP_N_FEATURES).iloc[::-1]
    fig, ax = plt.subplots(figsize=(10, 7))
    ax.barh(top["feature"], top["gain_importance"], color="#4C78A8")
    ax.set_xlabel("Gain importance")
    ax.set_title(f"Top {TOP_N_FEATURES} Features - XGBoost Gain")
    fig.tight_layout()
    fig.savefig(outdir / "feature_importance.png", dpi=150)
    plt.close(fig)

    # -- SHAP ---------------------------------------------------------------
    try:
        import shap

        explainer = shap.TreeExplainer(model)
        shap_values = explainer.shap_values(X_test)

        plt.figure()
        shap.summary_plot(shap_values, X_test, max_display=TOP_N_FEATURES, show=False)
        plt.title("SHAP Summary - Deal Score Drivers", fontsize=12)
        plt.tight_layout()
        plt.savefig(outdir / "shap_summary.png", dpi=150, bbox_inches="tight")
        plt.close()

        plt.figure()
        shap.summary_plot(
            shap_values, X_test, plot_type="bar", max_display=TOP_N_FEATURES, show=False
        )
        plt.title("Mean |SHAP| - Global Feature Impact", fontsize=12)
        plt.tight_layout()
        plt.savefig(outdir / "shap_importance_bar.png", dpi=150, bbox_inches="tight")
        plt.close()

        mean_abs = np.abs(shap_values).mean(axis=0)
        importance = importance.merge(
            pd.DataFrame({"feature": X_test.columns, "mean_abs_shap": mean_abs}),
            on="feature", how="left",
        )
        print("\n--- Top 15 by mean |SHAP| ---")
        print(
            importance.sort_values("mean_abs_shap", ascending=False)
            .head(TOP_N_FEATURES)[["feature", "mean_abs_shap"]]
            .to_string(index=False, float_format=lambda v: f"{v:.5f}")
        )
        print("\nSaved: shap_summary.png, shap_importance_bar.png")
    except Exception as exc:  # noqa: BLE001 - explainability is best-effort
        # Deliberately broad: SHAP failing (missing package, unsupported model,
        # plotting backend issue) must never destroy a completed training run.
        # The model and its metrics are already computed by this point.
        print(f"[WARN] SHAP stage skipped: {type(exc).__name__}: {exc}")

    return importance


# --------------------------------------------------------------------------- #
# STEP 10 - Persistence
# --------------------------------------------------------------------------- #

def build_provenance(source_csv: Path, n_rows: int, metrics: dict[str, float]) -> dict:
    """Record who/what/when produced a model, for auditability.

    Without this a deployed ``.pkl`` is anonymous: you cannot tell which data
    trained it, when, or under which library versions - which makes a stale or
    swapped artefact impossible to detect after the fact.
    """
    import platform

    import sklearn
    import xgboost

    digest = "unavailable"
    try:
        sha = hashlib.sha256()
        with open(source_csv, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                sha.update(chunk)
        digest = sha.hexdigest()
    except OSError:  # never let provenance collection break a good training run
        pass

    return {
        "pipeline_version": PIPELINE_VERSION,
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source_csv": str(source_csv),
        "source_sha256": digest,
        "source_rows": int(n_rows),
        "n_train_rows": int(metrics.get("n_train_rows", 0)),
        "n_test_rows": int(metrics.get("n_test_rows", 0)),
        "random_state": RANDOM_STATE,
        "library_versions": {
            "python": platform.python_version(),
            "numpy": np.__version__,
            "pandas": pd.__version__,
            "scikit-learn": sklearn.__version__,
            "xgboost": xgboost.__version__,
        },
    }


def save_artifacts(
    model: XGBRegressor,
    engineered: pd.DataFrame,
    importance: pd.DataFrame,
    ranking: pd.DataFrame,
    metrics: dict[str, float],
    best_params: dict,
    feature_names: list[str],
    ordinal_maps: dict[str, dict[str, int]],
    objection_tokens: list[str],
    onehot_features: list[str],
    scaler: MinMaxScaler | None,
    outdir: Path,
    provenance: dict | None = None,
) -> Path:
    """Persist the model bundle and every downstream artefact.

    The ``.pkl`` holds the model **and** its preprocessing contract, so
    inference never has to re-derive encodings from training data.

    Two copies are written: ``outputs/xgboost_deal_score.pkl`` (the mutable
    "current" pointer) and an immutable, timestamped copy under ``models/``.
    Overwriting the former is what previously allowed a stale model to serve
    predictions unnoticed; the archive means every run stays recoverable.
    """
    banner("STEP 10 | SAVE OUTPUTS")

    bundle = {
        "model": model,
        "feature_names": feature_names,
        "ordinal_maps": ordinal_maps,
        "objection_tokens": objection_tokens,
        "onehot_features": onehot_features,
        "scaler": scaler,
        "scaled_columns": [c for c in SCALED_COLUMNS],
        "target": TARGET,
        "provenance": provenance or {},
        "metrics": metrics,
        "best_params": best_params,
    }

    model_path = outdir / "xgboost_deal_score.pkl"
    joblib.dump(bundle, model_path)

    # Immutable archive copy: `outputs/` is overwritten by every run, so an
    # archived, timestamped artefact is the only way to reproduce or roll back
    # a model that has already scored production data.
    stamp = (provenance or {}).get("trained_at", "").replace(":", "").replace("-", "")
    stamp = stamp.replace("+0000", "Z").replace("T", "_") or "unstamped"
    archive_dir = outdir.parent / "models"
    archive_dir.mkdir(parents=True, exist_ok=True)
    archive_path = archive_dir / f"deal_score_v{PIPELINE_VERSION}_{stamp}.pkl"
    joblib.dump(bundle, archive_path)
    (archive_dir / f"{archive_path.stem}.provenance.json").write_text(
        json.dumps({**(provenance or {}), "metrics": metrics}, indent=2, default=str),
        encoding="utf-8",
    )

    engineered.to_csv(outdir / "feature_engineered_dataset.csv", index=False)
    importance.to_csv(outdir / "feature_importance.csv", index=False)
    ranking.to_csv(outdir / "feature_selection_ranking.csv")
    (outdir / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    (outdir / "best_params.json").write_text(json.dumps(best_params, indent=2, default=str), encoding="utf-8")

    for name in (
        "xgboost_deal_score.pkl",
        "feature_engineered_dataset.csv",
        "feature_importance.csv",
        "shap_summary.png",
        "correlation_heatmap.png",
        "feature_distributions.png",
        "target_distribution.png",
        "feature_importance.png",
        "metrics.json",
        "best_params.json",
    ):
        path = outdir / name
        status = f"{path.stat().st_size / 1024:8.1f} KB" if path.exists() else "  MISSING"
        print(f"  {status}  {name}")

    print(f"\n  Archived : {archive_path}")
    if provenance:
        print(f"  Version  : {provenance['pipeline_version']}  "
              f"trained {provenance['trained_at']}")
        print(f"  Source   : {Path(provenance['source_csv']).name} "
              f"({provenance['source_rows']} rows, sha256 {provenance['source_sha256'][:12]}...)")

    return model_path


# --------------------------------------------------------------------------- #
# STEP 11 - Inference
# --------------------------------------------------------------------------- #

def priority_band(score: float) -> tuple[str, str]:
    """Map a deal score onto a sales-priority band and recommended action."""
    if score >= 75:
        return "HIGH", "Prioritise - push for close this cycle."
    if score >= 50:
        return "MEDIUM", "Nurture - resolve open objections, confirm budget."
    if score >= 25:
        return "LOW", "Qualify further before investing sales capacity."
    return "VERY LOW", "Deprioritise or disqualify."


class SchemaError(ValueError):
    """Raised when incoming data violates the model's expected schema."""


def load_bundle(model_path: Path, require_version: bool = True) -> dict:
    """Load a model bundle, refusing artefacts this code cannot serve safely.

    A pickle produced by a different feature-engineering contract will still
    *load* but will silently mis-encode inputs, so the version is checked here
    rather than trusted.  Pass ``require_version=False`` to inspect a legacy
    bundle without serving it.
    """
    model_path = Path(model_path)
    if not model_path.exists():
        raise FileNotFoundError(f"No model at {model_path}")

    bundle = joblib.load(model_path)
    for key in ("model", "feature_names", "ordinal_maps", "target"):
        if key not in bundle:
            raise SchemaError(f"{model_path.name} is not a deal-score bundle (missing {key!r})")

    found = bundle.get("provenance", {}).get("pipeline_version")
    if require_version and found != PIPELINE_VERSION:
        raise SchemaError(
            f"{model_path.name} was built by pipeline version {found or 'unknown'}, "
            f"but this code is {PIPELINE_VERSION}. Retrain, or load with "
            "require_version=False if you have verified compatibility."
        )
    return bundle


def transform_for_inference(
    frame: pd.DataFrame, bundle: dict, report: bool = False, strict: bool = False
) -> pd.DataFrame:
    """Apply the stored training-time contract to any number of raw rows.

    Single source of truth for preprocessing at inference: ordinal maps,
    objection flags, one-hot columns, scaling and column ordering all come
    from the persisted bundle, never re-derived from the incoming data.  This
    is what keeps batch scoring consistent with training.
    """
    data = frame.copy()
    data.columns = [c.strip().lower() for c in data.columns]

    # The target must never reach the model, even if the caller supplied it.
    data = data.drop(columns=[bundle["target"]], errors="ignore")

    # Strict mode validates the *raw* CRM fields the caller must supply.  It
    # cannot validate post-encoding columns, because one-hot expansion of a
    # single record legitimately yields only the one dummy that applies.
    if strict:
        required_raw = set(bundle["ordinal_maps"]) | set(ONEHOT_COLUMNS) | {MULTILABEL_COLUMN}
        required_raw |= {c for c in bundle.get("scaled_columns", [])}
        absent = sorted(required_raw - set(data.columns))
        if absent:
            raise SchemaError(f"missing required input field(s): {absent}")

    # -- ordinal encoding ---------------------------------------------------
    for col, mapping in bundle["ordinal_maps"].items():
        if col in data.columns:
            median = int(np.median(list(mapping.values())))
            unseen = {
                normalise_label(v) for v in data[col].dropna().unique()
            } - set(mapping)
            if unseen:
                if strict:
                    raise SchemaError(
                        f"{col}: unrecognised value(s) {sorted(unseen)}. "
                        f"Expected one of {sorted(mapping)}."
                    )
                if report:
                    print(f"  [WARN] {col}: unseen label(s) {sorted(unseen)} -> imputed as {median}")
            data[col] = data[col].map(
                lambda v, m=mapping, d=median: m.get(normalise_label(v), d)
            ).astype(int)

    # -- multi-label objections --------------------------------------------
    if MULTILABEL_COLUMN in data.columns:
        parsed = data[MULTILABEL_COLUMN].fillna("").map(
            lambda raw: {
                normalise_label(p)
                for p in re.split(r"[;,]", str(raw))
                if normalise_label(p) and normalise_label(p) not in NULL_OBJECTION_TOKENS
            }
        )
        if report:
            seen = set().union(*parsed) if len(parsed) else set()
            unseen = seen - set(bundle["objection_tokens"])
            if unseen:
                print(f"  [WARN] unknown objection type(s) {sorted(unseen)} -> ignored")
        for token in bundle["objection_tokens"]:
            data[f"objection_{slugify(token)}"] = parsed.map(lambda s, t=token: int(t in s))
        data["num_objections"] = parsed.map(len).astype(int)
        data = data.drop(columns=[MULTILABEL_COLUMN])

    # -- one-hot encoding ---------------------------------------------------
    onehot_present = [c for c in ONEHOT_COLUMNS if c in data.columns]
    if onehot_present:
        data = pd.get_dummies(data, columns=onehot_present, prefix=onehot_present)

    # -- scaling ------------------------------------------------------------
    scaler = bundle.get("scaler")
    scale_cols = [c for c in bundle.get("scaled_columns", []) if c in data.columns]
    if scaler is not None and scale_cols:
        data[scale_cols] = scaler.transform(data[scale_cols])

    # -- safety net: nothing non-numeric may reach the model ----------------
    #
    # A column the training data held as a number (and therefore never entered
    # `ordinal_maps`) can still arrive as text from a hand-written record or a
    # differently-typed export.  Coerce loudly rather than crashing on astype.
    for col in [c for c in data.columns if not pd.api.types.is_numeric_dtype(data[c])]:
        coerced = pd.to_numeric(data[col], errors="coerce")
        if report and coerced.isna().any():
            bad = data.loc[coerced.isna(), col].unique()[:5]
            print(f"  [WARN] {col}: non-numeric value(s) {list(bad)} -> 0 "
                  "(column is numeric in training data)")
        data[col] = coerced.fillna(0)

    # -- align to the training schema --------------------------------------
    expected = bundle["feature_names"]
    missing = [c for c in expected if c not in data.columns]
    extra = [c for c in data.columns if c not in expected]

    # One-hot dummies are exempt: a record belongs to exactly one category, so
    # the other dummies are correctly absent and fill with 0.  Anything else
    # missing at this point is genuine schema drift.
    onehot_generated = set(bundle.get("onehot_features", []))
    drift = [c for c in missing if c not in onehot_generated]
    if strict and drift:
        raise SchemaError(f"{len(drift)} required feature(s) absent: {drift[:10]}")

    if report:
        if missing:
            print(f"  [WARN] {len(missing)} feature(s) absent -> filled with 0: {missing[:8]}"
                  f"{' ...' if len(missing) > 8 else ''}")
        if extra:
            print(f"  [INFO] {len(extra)} unrecognised column(s) ignored: {extra[:8]}"
                  f"{' ...' if len(extra) > 8 else ''}")

    return data.reindex(columns=expected, fill_value=0).astype(float)


def prepare_record(record: dict, bundle: dict) -> pd.DataFrame:
    """Transform one raw CRM record into a model-ready single-row frame."""
    return transform_for_inference(pd.DataFrame([record]), bundle)


def score_csv(csv_path: Path, model_path: Path, outdir: Path) -> pd.DataFrame:
    """Score a user-supplied CSV with the trained model.

    If the file carries a ``deal_score`` column the model is *evaluated*
    against it (full metric suite); otherwise predictions are produced blind.
    Either way a ``scored_predictions.csv`` is written.
    """
    banner("BATCH SCORING | USER-SUPPLIED CSV")

    if not model_path.exists():
        raise FileNotFoundError(
            f"No trained model at {model_path}. Run the pipeline without "
            "--score-csv first, or pass --model."
        )
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV not found: {csv_path}")

    bundle = joblib.load(model_path)
    model = bundle["model"]
    target = bundle["target"]

    raw = pd.read_csv(csv_path, keep_default_na=False, na_values=NA_VALUES)
    raw.columns = [c.strip().lower() for c in raw.columns]
    print(f"Input   : {csv_path}")
    print(f"Model   : {model_path}")
    print(f"Shape   : {raw.shape[0]} rows x {raw.shape[1]} columns")

    print("\n--- Schema alignment ---")
    features = transform_for_inference(raw, bundle, report=True)
    print(f"  Aligned to {features.shape[1]} training features.")

    predictions = np.clip(model.predict(features), 0, 100)

    scored = raw.copy()
    scored["predicted_deal_score"] = predictions.round(2)
    bands = [priority_band(float(p)) for p in predictions]
    scored["priority_band"] = [b for b, _ in bands]
    scored["recommended_action"] = [a for _, a in bands]

    # -- evaluation, when ground truth is available -------------------------
    if target in raw.columns:
        y_true = pd.to_numeric(raw[target], errors="coerce")
        valid = y_true.notna()
        if valid.sum() == 0:
            print(f"\n[WARN] '{target}' present but unparseable -> predictions only.")
        else:
            scored["absolute_error"] = (y_true - predictions).abs().round(2)
            yt = y_true[valid].to_numpy()
            yp = predictions[valid.to_numpy()]

            mae = mean_absolute_error(yt, yp)
            mse = mean_squared_error(yt, yp)
            rmse = float(np.sqrt(mse))
            mape, excluded = safe_mape(yt, yp)
            r2 = r2_score(yt, yp)
            n, p = len(yt), features.shape[1]
            adj = 1 - (1 - r2) * (n - 1) / (n - p - 1) if n > p + 1 else float("nan")

            print(f"\n--- Performance against '{target}' ({n} labelled rows) ---")
            print(f"  MAE          : {mae:.4f}")
            print(f"  MSE          : {mse:.4f}")
            print(f"  RMSE         : {rmse:.4f}")
            print(f"  MAPE         : {mape:.4f} %"
                  + (f"   ({excluded} zero actual(s) excluded)" if excluded else ""))
            print(f"  sMAPE        : {smape(yt, yp):.4f} %")
            print(f"  R2 Score     : {r2:.4f}")
            print(f"  Adjusted R2  : {adj:.4f}" if n > p + 1
                  else f"  Adjusted R2  : n/a (needs > {p + 1} rows)")

            within = [(1, 0), (3, 0), (5, 0), (10, 0)]
            errors = np.abs(yt - yp)
            print("\n  Error tolerance breakdown:")
            for tol, _ in within:
                print(f"    within +/-{tol:<3d} points : {100 * (errors <= tol).mean():5.1f} %")
    else:
        print(f"\nNo '{target}' column found -> prediction-only mode (no metrics).")

    print("\n--- Priority band distribution ---")
    print(scored["priority_band"].value_counts().to_string())

    out_path = outdir / "scored_predictions.csv"
    scored.to_csv(out_path, index=False)
    print(f"\nSaved: {out_path}")
    return scored


def predict_single_record(model_path: Path, template: pd.Series | None = None) -> None:
    """Demonstrate scoring one brand-new customer record from the saved bundle.

    When ``template`` is supplied (a raw row from the training data) it is used
    as the demo record, which keeps this step valid across datasets with
    different category vocabularies.  The hard-coded record below is only a
    fallback for standalone use.
    """
    banner("STEP 11 | PREDICTION - NEW CUSTOMER RECORD")

    bundle = joblib.load(model_path)
    model = bundle["model"]

    new_customer = {
        "total_meetings": 7,
        "lead_score": 82,
        "customer_sentiment": "Positive",
        "buying_intent": "High",
        "relationship_strength": "Strong",
        "budget_status": "Confirmed",
        "decision_maker_involvement": "Present",
        "customer_urgency": "High",
        "main_objections": "Timeline, Integration",
        "product_interest_level": "High",
        "meeting_outcome": "Positive",
        "customer_requirements": "Complete",
        "risk_factors": "Low",
        "competitor_mention": "No",
        "engagement_score": 9,
        "implementation_readiness": "Ready",
        "upsell_opportunity": "High",
    }

    if template is not None:
        new_customer = template.drop(labels=[bundle["target"]], errors="ignore").to_dict()

    print("--- Raw input record ---")
    for key, value in new_customer.items():
        print(f"  {key:<30} {value}")

    features = prepare_record(new_customer, bundle)
    score = float(model.predict(features)[0])
    score = float(np.clip(score, 0, 100))

    band, action = priority_band(score)

    print(f"\n>>> PREDICTED DEAL SCORE : {score:.2f} / 100")
    print(f">>> PRIORITY BAND        : {band}")
    print(f">>> RECOMMENDED ACTION   : {action}")

    # Per-prediction SHAP attribution - why this deal scored the way it did.
    try:
        import shap

        explainer = shap.TreeExplainer(model)
        contributions = explainer.shap_values(features)[0]
        drivers = (
            pd.DataFrame({"feature": features.columns, "shap_value": contributions})
            .assign(magnitude=lambda d: d["shap_value"].abs())
            .sort_values("magnitude", ascending=False)
            .head(10)
        )
        print("\n--- Top 10 drivers for this prediction ---")
        for _, row in drivers.iterrows():
            arrow = "^" if row["shap_value"] > 0 else "v"
            print(f"  {arrow} {row['feature']:<38} {row['shap_value']:+.3f}")
    except Exception as exc:  # noqa: BLE001 - attribution is best-effort
        # The prediction above already succeeded and has been printed; a SHAP
        # failure must not turn a valid score into a crash.
        print(f"[WARN] Per-record SHAP skipped: {type(exc).__name__}: {exc}")


# --------------------------------------------------------------------------- #
# Orchestration
# --------------------------------------------------------------------------- #

def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="CRM Deal Score - XGBoost pipeline")
    parser.add_argument(
        "--csv",
        type=Path,
        default=Path(r"C:/Users/shams/Downloads/deal_score_dataset_1000_v3.csv"),
        help="Path to the raw CRM deal-score CSV (training data).",
    )
    parser.add_argument(
        "--outdir",
        type=Path,
        default=Path(__file__).resolve().parent / "outputs",
        help="Directory for models, plots and CSV artefacts.",
    )
    parser.add_argument(
        "--n-iter", type=int, default=60,
        help="RandomizedSearchCV candidate count.",
    )
    parser.add_argument(
        "--score-csv",
        type=Path,
        default=None,
        help=(
            "Score YOUR OWN CSV with the trained model. If the file contains a "
            "'deal_score' column the model is evaluated against it (MAE/RMSE/R2/...); "
            "otherwise predictions are produced blind. Writes scored_predictions.csv. "
            "Skips training when a saved model already exists."
        ),
    )
    parser.add_argument(
        "--model",
        type=Path,
        default=None,
        help="Path to an existing xgboost_deal_score.pkl (defaults to <outdir>/xgboost_deal_score.pkl).",
    )
    parser.add_argument(
        "--retrain",
        action="store_true",
        help="Force a full retrain even when --score-csv is given and a model exists.",
    )
    parser.add_argument(
        "--metrics-only",
        action="store_true",
        help=(
            "Suppress all pipeline output and print only the final metrics table "
            "(training and test scores side by side)."
        ),
    )
    return parser.parse_args(argv)


def print_metrics_report(metrics: dict[str, float], n_features: int, n_test: int) -> None:
    """Print training and test metrics side by side, with nothing else."""

    def fmt(value: float, width: int = 13) -> str:
        """Render a metric, or explain why adjusted R2 is undefined."""
        return f"{value:>{width}.4f}" if np.isfinite(value) else f"{'undefined':>{width}}"

    excluded = metrics.get("mape_excluded_zeros", 0)
    rows = (
        ("MAE", "train_mae", "mae", ""),
        ("MSE", "train_mse", "mse", ""),
        ("RMSE", "train_rmse", "rmse", ""),
        ("MAPE", "train_mape_percent", "mape_percent", " %"),
        ("R2 Score", "train_r2", "r2", ""),
        ("Adjusted R2", "train_adjusted_r2", "adjusted_r2", ""),
    )

    width = 58
    print("=" * width)
    print("MODEL EVALUATION METRICS")
    print("=" * width)
    print(f"  {'Metric':<20}{'TRAIN':>13}{'TEST':>13}")
    print(f"  {'-' * 20}{'-' * 13:>13}{'-' * 13:>13}")
    for label, train_key, test_key, suffix in rows:
        print(f"  {label:<20}{fmt(metrics[train_key])}{fmt(metrics[test_key])}{suffix}")

    print(f"\n  Cross Validation    : {metrics['cv_r2_mean']:.4f} +/- "
          f"{metrics['cv_r2_std']:.4f}  (5-fold R2 on training data)")
    gap = metrics["train_test_r2_gap"]
    verdict = "healthy" if gap < 0.10 else ("mild overfit" if gap < 0.25 else "OVERFITTING")
    print(f"  Train-Test R2 gap   : {gap:.4f}  -> {verdict}")
    print(f"  Rows                : train={int(metrics['n_train_rows'])}  "
          f"test={int(metrics['n_test_rows'])}  features={n_features}")

    if not np.isfinite(metrics["adjusted_r2"]):
        print(f"\n  Adjusted R2 undefined: needs > {n_features + 1} test rows, have {n_test}.")
    if excluded:
        print(f"  MAPE excludes {excluded} zero-valued actual(s) - undefined at 0.")
    print("\n  Training scores are in-sample and always optimistic;")
    print("  only the TEST column estimates real-world performance.")
    print("=" * width)


def run_training_pipeline(args: argparse.Namespace, outdir: Path) -> tuple[dict[str, float], Path, int, int]:
    """Execute steps 1-11 and return ``(metrics, model_path, n_features, n_test)``.

    Split out from ``main`` so the whole pipeline can be run with its output
    captured (see ``--metrics-only``).
    """
    # Steps 1-3.
    raw = load_dataset(args.csv)
    cleaned = clean_dataset(raw)
    engineered, ordinal_maps, objection_tokens, onehot_features, scaler = engineer_features(cleaned)

    # Step 4.
    corr = run_eda(engineered, outdir)

    # Step 5.
    X = engineered.drop(columns=[TARGET])
    y = engineered[TARGET]
    ranking = select_features(X, y, corr)

    # Step 6.
    banner("STEP 6 | TRAIN / TEST SPLIT")
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=TEST_SIZE, random_state=RANDOM_STATE
    )
    print(f"Train : {X_train.shape[0]} rows ({1 - TEST_SIZE:.0%})")
    print(f"Test  : {X_test.shape[0]} rows ({TEST_SIZE:.0%})")
    print(f"Features : {X_train.shape[1]}")
    print(f"Target mean  train={y_train.mean():.3f}  test={y_test.mean():.3f}")

    # Steps 7-9.
    model, best_params, _ = train_model(X_train, y_train, args.n_iter)
    metrics = evaluate_model(model, X_train, y_train, X_test, y_test)
    importance = explain_model(model, X_train, X_test, outdir)

    # Steps 10-11.
    provenance = build_provenance(args.csv, len(engineered), metrics)
    model_path = save_artifacts(
        model, engineered, importance, ranking, metrics, best_params,
        list(X.columns), ordinal_maps, objection_tokens, onehot_features,
        scaler, outdir, provenance,
    )
    # Use a real row from the source data so the demo stays valid whatever the
    # dataset's category vocabulary happens to be.
    predict_single_record(model_path, template=cleaned.iloc[0])

    # Optional: score a user-supplied CSV with the model just trained.
    if args.score_csv is not None:
        score_csv(args.score_csv, model_path, outdir)

    banner("PIPELINE COMPLETE", "#")
    print(f"Test R2   : {metrics['r2']:.4f}")
    print(f"Test RMSE : {metrics['rmse']:.4f}")
    print(f"Artefacts : {outdir}")
    return metrics, model_path, X_train.shape[1], X_test.shape[0]


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    outdir: Path = args.outdir
    outdir.mkdir(parents=True, exist_ok=True)

    model_path = args.model or (outdir / "xgboost_deal_score.pkl")
    scoring_only = (
        args.score_csv is not None and model_path.exists() and not args.retrain
    )

    # --metrics-only: run everything with stdout captured, then emit just the
    # metrics block.  Artefacts are still written; only the console is quiet.
    if args.metrics_only:
        sink = io.StringIO()
        try:
            with contextlib.redirect_stdout(sink):
                if scoring_only:
                    raise SystemExit(
                        "--metrics-only requires training; drop --score-csv or add --retrain."
                    )
                metrics, _, n_features, n_test = run_training_pipeline(args, outdir)
        except BaseException:
            # Surface the captured output so a failure is diagnosable.
            print(sink.getvalue(), file=sys.stderr)
            raise
        print_metrics_report(metrics, n_features, n_test)
        return 0

    banner("CRM DEAL SCORING | XGBOOST REGRESSION PIPELINE", "#")
    print(f"Output directory : {outdir}")
    print(f"Random state     : {RANDOM_STATE}")

    # Fast path: score a user-supplied CSV against an already-trained model
    # without re-running the (expensive) training pipeline.
    if scoring_only:
        print(f"Mode             : scoring only (reusing {model_path.name})")
        score_csv(args.score_csv, model_path, outdir)
        banner("SCORING COMPLETE", "#")
        return 0

    run_training_pipeline(args, outdir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
