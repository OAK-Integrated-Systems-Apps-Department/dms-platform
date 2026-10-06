package com.oak.dms.watermark.service;

import com.oak.dms.document.DocumentContentReader.VersionDetails;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns {@code "CONFIDENTIAL - {username} {date}"} into the exact words that
 * will be drawn on the page.
 *
 * <h2>Resolved at request time, once, and never again</h2>
 * Every token is substituted on the request thread, and the result is
 * snapshotted onto the job. The worker reads that snapshot and nothing else.
 * This is what makes the audit entry worth having: the text recorded when
 * the copy was asked for is necessarily the text on the copy, even if the
 * user was renamed, the template rewritten or the clock moved on before the
 * job ran. Re-resolving in the worker would quietly break that.
 *
 * <h2>Unknown tokens are an error, not literal text</h2>
 * A typo like {@code {usernmae}} left as-is would print the typo onto
 * somebody's document. Worse, a user who believes they are stamping their
 * own name on a copy would be stamping nothing identifying at all. So an
 * unrecognised token is a 400 that names it and lists what is available.
 *
 * <h2>Extending it</h2>
 * {@link #RESOLVERS} is the whole registry. Phase 2's department, IP
 * address or classification tokens are one entry each; nothing else in the
 * feature needs to change.
 */
@Component
@RequiredArgsConstructor
public class PlaceholderResolver {

    /** {@code {name}} - deliberately strict, so a stray brace is not mistaken for a token. */
    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    /**
     * UTC, always, and labelled as such. A watermark is read by someone who
     * has no idea which timezone the server or the requester was in, and an
     * unlabelled local time on a document is worse than no time at all.
     */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT);

    /**
     * Everything that may appear in a watermark. Each value is sanitised and
     * length-capped before substitution - see {@link TextSanitizer} - so a
     * display name cannot smuggle a bidi override onto the page or crowd out
     * the rest of the line.
     */
    private static final Map<String, Function<Context, String>> RESOLVERS = new LinkedHashMap<>(Map.of(
            "username", context -> context.user().map(User::getUsername).orElse(""),
            "userEmail", context -> context.user().map(User::getEmail).orElse(""),
            "date", context -> context.requestedAt().withOffsetSameInstant(ZoneOffset.UTC).format(DATE_FORMAT),
            "documentId", context -> context.version().documentId().toString(),
            "documentVersion", context -> String.valueOf(context.version().versionNumber()),
            // The first 8 characters of the job id, drawn on the page so a
            // leaked copy can be traced back to the audit entry that produced
            // it. Visible text, never hidden - this is not steganography, and
            // the person being identified can see that they are.
            "watermarkId", context -> context.jobId().toString().substring(0, 8)));

    private final UserRepository userRepository;
    private final TextSanitizer sanitizer;

    /**
     * @param jobId       assigned BEFORE this is called - {@code {watermarkId}}
     *                    draws it, so it has to already be the id that will be
     *                    stored. See {@code WatermarkJob}.
     * @param requestedAt the request's own clock reading, so every token in
     *                    one watermark agrees on the time
     */
    public record Context(UUID jobId, VersionDetails version, OffsetDateTime requestedAt,
                          Optional<User> user) {
    }

    public String resolve(String template, Context context) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder result = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            String token = matcher.group(1);
            Function<Context, String> resolver = RESOLVERS.get(token);
            if (resolver == null) {
                throw WatermarkException.badRequest("WATERMARK_UNKNOWN_PLACEHOLDER",
                        "'{" + token + "}' isn't a watermark placeholder. Available: " + available() + ".");
            }
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(sanitizer.token(resolver.apply(context))));
        }
        matcher.appendTail(result);
        // Sanitised again as a whole: the template's own literal text has had
        // no cleaning yet, and a value ending mid-word next to literal text
        // can leave a double space.
        return sanitizer.text(result.toString());
    }

    /** The caller's user row, for the identity tokens. Empty rather than failing if it has gone. */
    public Optional<User> effectiveUser() {
        UUID userId = TenantContext.getUserId();
        return userId == null ? Optional.empty() : userRepository.findById(userId);
    }

    public static String available() {
        return RESOLVERS.keySet().stream().map(name -> "{" + name + "}")
                .reduce((a, b) -> a + ", " + b).orElse("");
    }
}
