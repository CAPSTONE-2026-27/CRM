# Contract email templates

The email a customer receives from `POST /api/contracts/{contractId}/send-email`.

| File | What it is |
|---|---|
| `contract-email-subject.txt` | Subject line (one line) |
| `contract-email.html` | Formatted body |
| `contract-email.txt` | Plain-text body, for mail clients that do not show HTML |

**Edit them any time.** They are read on every send, so the next email uses your
change — no rebuild, no restart. The contract PDF is attached automatically.

## Placeholders

| Placeholder | Example |
|---|---|
| `${signerName}` | Meera Deshpande |
| `${contractNumber}` | CTR-000005 |
| `${contractTitle}` | Standard Sales Agreement |
| `${senderName}` | `mailjet.from-name` |
| `${senderEmail}` | `mailjet.from-email` |

Values are HTML-escaped in the HTML body, so names containing `&` or `'` are safe.
A misspelled placeholder is left visible in the email and logged as a warning, so
send yourself a test after editing:

```
POST /api/contracts/{contractId}/send-email
{ "recipientEmail": "you@example.com", "resend": true }
```

Keep both bodies saying the same thing — some customers only see the plain-text one.

## Location

Read from `mailjet.templates.location`, default `file:./email-templates/`, which is
relative to the directory the backend is started from (`backend/`). Point it
elsewhere to keep the wording outside the repository.
