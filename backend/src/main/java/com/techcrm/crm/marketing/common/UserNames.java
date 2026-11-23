package com.techcrm.crm.marketing.common;

import com.techcrm.crm.user.User;
import com.techcrm.crm.user.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves user ids to display names for audit trails, restricted to the caller's organization. */
@Component
public class UserNames {

    private final UserRepository users;

    public UserNames(UserRepository users) {
        this.users = users;
    }

    public Map<Long, String> resolve(Long organizationId, Collection<Long> ids) {
        Set<Long> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> out = new HashMap<>();
        if (wanted.isEmpty()) return out;
        for (User u : users.findAllById(wanted)) {
            if (Objects.equals(u.getOrganizationId(), organizationId)) out.put(u.getId(), u.getFullName());
        }
        return out;
    }
}
