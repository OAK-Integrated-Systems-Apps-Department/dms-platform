package com.oak.dms.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for provisioning a new tenant. domain is validated as a
 * lowercase hostname since it doubles as the tenant's login domain.
 */
public record CreateTenantRequest(

        @NotBlank
        @Size(max = 255)
        String tenantName,

        @NotBlank
        @Size(max = 255)
        // Two deliberate departures from the obvious hostname regex
        // [a-z0-9]([a-z0-9-]*[a-z0-9])?(\.…)*:
        //
        // 1. Alternating runs. [a-z0-9-]* and the [a-z0-9] after it can both
        //    match the same character, so every character has several places
        //    it could go. Here a hyphen only matches -+ and anything else
        //    only [a-z0-9]+, so there is exactly one way to match.
        //
        // 2. COUNTED repetitions, not *. Java's regex engine recurses once
        //    per iteration of a repeated group, so an unbounded * overflows
        //    the stack on a long input - measured: a 20 KB domain throws
        //    StackOverflowError, which surfaces as a 500 from a request that
        //    costs nothing to send. @Size(max = 255) does not save us,
        //    because Bean Validation does not promise to run it first.
        //    127 is the most either group can repeat within those 255
        //    characters, so nothing @Size permits is newly rejected -
        //    an oversized string is now refused in microseconds instead.
        @Pattern(regexp = "^[a-z0-9]+(-+[a-z0-9]+){0,127}(\\.[a-z0-9]+(-+[a-z0-9]+){0,127}){0,127}$",
                message = "domain must be a valid lowercase hostname, e.g. acme.oakdms.com")
        String domain,

        @NotBlank
        String subscriptionTier
) {
}
