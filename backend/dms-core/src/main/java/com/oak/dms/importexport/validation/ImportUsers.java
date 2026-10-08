package com.oak.dms.importexport.validation;

import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * How a {@code user_ref} cell's email is resolved - shared by row validation
 * and record creation (through {@link ImportValueConverter}) so both always
 * agree on who a cell refers to.
 */
@Component
@RequiredArgsConstructor
public class ImportUsers {

    private final UserRepository userRepository;

    /** Resolves a trimmed email to an active, non-deleted user's id in {@code tenantId}. */
    public Function<String, Optional<UUID>> activeUserByEmail(UUID tenantId) {
        return email -> userRepository
                .findByTenantIdAndEmailAndDeletedFalse(tenantId, email)
                .filter(user -> user.getStatus() == User.UserStatus.active)
                .map(User::getUserId);
    }
}
