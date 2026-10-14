package com.oak.dms.common.sql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build when a SQL string is joined to something that isn't a constant.
 * <p>
 * Every value in this code base reaches the database as a bound parameter; the only {@code +} allowed
 * inside SQL is one that joins fixed text - a {@code static final} constant (UPPER_CASE) written
 * in the code. A variable, a method call or a request value spliced in is how SQL injection
 * happens, and is caught here at review time instead of by someone finding it later.
 * <p>
 * It is a text check, not a parser: it looks at string literals that read as SQL and at what they are
 * joined to. It will not see SQL built in some roundabout way, and it is no substitute for the
 * SonarQube scan (rule S2077) - it is the part of it that runs on every build.
 */
class SqlConcatenationGuardTest {

    /** Text that reads as a SQL statement, rather than an English sentence that happens to say "from". */
    private static final Pattern SQL_TEXT = Pattern.compile(
            "(?is)\\bselect\\b.+\\bfrom\\b|\\binsert\\s+into\\b|\\bupdate\\s+\\w+\\s+set\\b|\\bdelete\\s+from\\b");

    /** A string literal or text block, then {@code + operand}. */
    private static final Pattern LITERAL_THEN_OPERAND = Pattern.compile(
            "(\"\"\"(?:(?!\"\"\").)*\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\")\\s*\\+\\s*([A-Za-z_][\\w.]*(?:\\([^)]*\\))?)",
            Pattern.DOTALL);

    /** An operand, then {@code + literal}. */
    private static final Pattern OPERAND_THEN_LITERAL = Pattern.compile(
            "([A-Za-z_][\\w.]*(?:\\([^)]*\\))?)\\s*\\+\\s*(\"\"\"(?:(?!\"\"\").)*\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\")",
            Pattern.DOTALL);

    /**
     * Operands that are not UPPER_CASE constants but are still only ever fixed text. Each needs a reason;
     * adding one here is a review decision, not a way round the check.
     */
    private static final Set<String> ALLOWED = Set.of(
            // DigitalImpactService: a local built once from two constants (and the VISIBLE filter's
            // named parameters), appended to; no request value is ever part of it.
            "versionsOf");

    /** {@code TEMPLATE.formatted("a.col", "b.col")}: a constant template filled with fixed text only. */
    private static final Pattern CONSTANT_TEMPLATE = Pattern.compile(
            "[A-Z][A-Z0-9_]*\\.formatted\\(\\s*\"[^\"]*\"(?:\\s*,\\s*\"[^\"]*\")*\\s*\\)");

    private static boolean isConstant(String operand) {
        if (CONSTANT_TEMPLATE.matcher(operand).matches()) {
            return true;
        }
        String name = operand.contains("(") ? "" : operand.substring(operand.lastIndexOf('.') + 1);
        return name.matches("[A-Z][A-Z0-9_]*") || ALLOWED.contains(operand);
    }

    @Test
    void noSqlIsJoinedToAnythingButAConstant() throws IOException {
        List<String> offences = new ArrayList<>();
        Path root = Path.of("src/main/java");
        assertThat(root).as("run from the dms-core module").isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file);
                check(file, source, LITERAL_THEN_OPERAND, 1, 2, offences);
                check(file, source, OPERAND_THEN_LITERAL, 2, 1, offences);
            }
        }
        assertThat(offences)
                .as("SQL joined to a non-constant - pass it as a bound :parameter instead")
                .isEmpty();
    }

    private static void check(Path file, String source, Pattern pattern, int literalGroup, int operandGroup,
                              List<String> offences) {
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            String literal = m.group(literalGroup);
            String operand = m.group(operandGroup);
            if (SQL_TEXT.matcher(literal).find() && !isConstant(operand)) {
                int line = (int) source.substring(0, m.start(operandGroup)).chars().filter(c -> c == '\n').count() + 1;
                offences.add(file + ":" + line + " joins SQL to '" + operand + "'");
            }
        }
    }

    @Test
    void theCheckActuallyCatchesInjectionShapedCode() {
        // Guards the guard: a variable spliced into SQL, either side, must be flagged...
        assertThat(flagged("String q = \"select * from users where name = '\" + name + \"'\";")).isTrue();
        assertThat(flagged("String q = userInput + \" select * from users\";")).isTrue();
        assertThat(flagged("String q = \"\"\"\n select id from t where x = \"\"\" + request.getParam();")).isTrue();
        // ...and fixed text must not be.
        assertThat(flagged("String q = \"select * from users where \" + VISIBLE;")).isFalse();
        assertThat(flagged("String q = \"select \" + DashboardService.EXTENSION + \" as ext from t\";")).isFalse();
        assertThat(flagged("log.info(\"Removed from folder \" + id);")).isFalse();
        // A constant template filled with literal column names is fixed text; filled with a variable it is not.
        assertThat(flagged("String q = \"select a from t where \" + NO_KEY.formatted(\"v.col\", \"v.key\");")).isFalse();
        assertThat(flagged("String q = \"select a from t where \" + NO_KEY.formatted(column);")).isTrue();
    }

    private static boolean flagged(String source) {
        List<String> offences = new ArrayList<>();
        check(Path.of("Example.java"), source, LITERAL_THEN_OPERAND, 1, 2, offences);
        check(Path.of("Example.java"), source, OPERAND_THEN_LITERAL, 2, 1, offences);
        return !offences.isEmpty();
    }
}
