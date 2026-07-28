package com.techcrm.crm.user;

import org.springframework.data.jpa.domain.Specification;

/** Lighter counterpart to LeadSpecifications — org-scope and
 *  exclude-soft-deleted are always mandatory, applied by the caller before
 *  any of these. No pagination this pass (realistic headcounts don't
 *  justify it yet); structured so adding it later is additive. */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    public static Specification<User> organizationId(Long organizationId) {
        return (root, query, cb) -> cb.equal(root.get("organizationId"), organizationId);
    }

    public static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<User> role(Role role) {
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    public static Specification<User> status(UserStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<User> departmentEquals(String department) {
        String pattern = "%" + department.toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(cb.coalesce(root.get("department"), "")), pattern);
    }

    /** Free-text OR across fullName/email/username. */
    public static Specification<User> nameOrEmailContains(String value) {
        String pattern = "%" + value.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("fullName")), pattern),
                cb.like(cb.lower(root.get("email")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("username"), "")), pattern)
        );
    }
}
