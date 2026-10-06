package com.oak.dms.dashboard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The home dashboard. Any signed-in user may call it - what's in it is limited to what they can see
 * (DashboardService), so no permission is checked here.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "The home dashboard's figures for the caller")
public class DashboardController {

    private final DashboardService dashboardService;
    private final DigitalImpactService digitalImpactService;

    /**
     * @param timeZone the browser's IANA time zone (e.g. Asia/Colombo), so the days are the caller's own
     * @param days     the period - 7, 30 or 90 days ending today (anything else means 30)
     */
    @GetMapping
    @Operation(summary = "Documents, storage, usage per day, types, formats, folders, most opened, security and activity")
    public DashboardResponse dashboard(@RequestParam(name = "timeZone", required = false) String timeZone,
                                       @RequestParam(name = "days", defaultValue = "30") int days) {
        return dashboardService.forCaller(zone(timeZone), days);
    }

    /**
     * The Digital Impact: pages processed digitally (counted from the files) and the paper and
     * footprint they stand for, with the method behind the estimates.
     *
     * @param days     7, 30 or 90 days ending today, unless {@code from} and {@code to} are given
     * @param from     a custom period's first day (with {@code to}; at most 366 days)
     * @param to       a custom period's last day - not in the future
     */
    @GetMapping("/impact")
    @Operation(summary = "Pages processed digitally, estimated paper, CO2e and water avoided, trend and method")
    public DigitalImpactResponse impact(@RequestParam(name = "timeZone", required = false) String timeZone,
                                        @RequestParam(name = "days", defaultValue = "30") int days,
                                        @RequestParam(name = "from", required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(name = "to", required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return digitalImpactService.forCaller(zone(timeZone), days, from, to);
    }

    static ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZone.strip());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
