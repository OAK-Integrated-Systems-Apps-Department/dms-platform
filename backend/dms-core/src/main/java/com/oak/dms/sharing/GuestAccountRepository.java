package com.oak.dms.sharing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuestAccountRepository extends JpaRepository<GuestAccount, UUID> {

    Optional<GuestAccount> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<GuestAccount> findByGuestIdAndTenantId(UUID guestId, UUID tenantId);

    Optional<GuestAccount> findByInviteTokenHash(String inviteTokenHash);

    /** Every organisation this address was invited to - a portal sign-in doesn't say which one. */
    List<GuestAccount> findByEmail(String email);

    List<GuestAccount> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** Active guests whose last sign-in (or, if they never signed in, their creation) is older than the cutoff. */
    @Query("select g from GuestAccount g where g.status = 'active' "
            + "and coalesce(g.lastLoginAt, g.createdAt) < :cutoff and g.tenantId = :tenantId")
    List<GuestAccount> findInactive(@Param("tenantId") UUID tenantId, @Param("cutoff") OffsetDateTime cutoff);
}
