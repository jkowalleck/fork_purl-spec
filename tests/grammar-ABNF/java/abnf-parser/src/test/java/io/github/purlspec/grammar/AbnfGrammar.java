// SPDX-License-Identifier: MIT
// Copyright (c) the purl authors
// Visit https://github.com/package-url/purl-spec and https://packageurl.org for support

package io.github.purlspec.grammar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Self-contained ABNF-to-regex compiler for the PURL specification grammar.
 *
 * <p>Extracts an {@code ```abnf} fenced code block from a Markdown file, parses
 * the RFC 5234 ABNF rule definitions, and compiles each named rule to a
 * {@link Pattern} that fully matches a string against that rule.
 *
 * <p>Supported ABNF constructs (RFC 5234 subset used by the PURL grammar):
 * <ul>
 *   <li>Terminal strings: {@code "literal"} (case-insensitive per RFC 5234 §2.3)</li>
 *   <li>Hex values: {@code %xHH}, {@code %xHH.HH} (concatenation), {@code %xHH-HH} (range)</li>
 *   <li>Core rules: {@code ALPHA}, {@code DIGIT}, {@code HEXDIG} (RFC 5234 Appendix B)</li>
 *   <li>Alternation: {@code rule1 / rule2}</li>
 *   <li>Concatenation: space-separated elements</li>
 *   <li>Repetition: {@code *elem}, {@code 1*elem}, {@code n*m elem}</li>
 *   <li>Optional group: {@code [elem]}</li>
 *   <li>Group: {@code (elem)}</li>
 *   <li>Rule references (case-insensitive lookup)</li>
 *   <li>Comments: {@code ; text to end-of-line}</li>
 * </ul>
 */
public class AbnfGrammar {

    // ─────────────────────────────────────────────────────────────────────────
    // Core RFC 5234 Appendix B rules (pre-seeded into the regex cache)
    // ─────────────────────────────────────────────────────────────────────────

    private static final Map<String, String> CORE_RULES;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("alpha",  "[A-Za-z]");
        m.put("digit",  "[0-9]");
        m.put("hexdig", "[0-9A-Fa-f]");
        CORE_RULES = Collections.unmodifiableMap(m);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    /** Lower-cased rule name → raw rule-body text (continuation lines joined, comments stripped). */
    private final Map<String, String> ruleTexts;

    /** Cache of compiled regex strings (populated lazily; pre-seeded with core rules). */
    private final Map<String, String> regexCache = new HashMap<>();

    private AbnfGrammar(Map<String, String> ruleTexts) {
        this.ruleTexts = ruleTexts;
        regexCache.putAll(CORE_RULES);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract the first {@code ```abnf} block from {@code markdownFile} and
     * parse it into an {@code AbnfGrammar} instance.
     */
    public static AbnfGrammar fromMarkdown(Path markdownFile) throws IOException {
        String md = Files.readString(markdownFile);
        String abnf = extractAbnfBlock(md);
        return new AbnfGrammar(parseRules(abnf));
    }

    /**
     * Compile the named rule to a {@link Pattern} that fully matches a string.
     *
     * @param ruleName ABNF rule name (case-insensitive)
     * @return compiled {@link Pattern}; use {@link Matcher#matches()} for full-string validation
     * @throws NoSuchElementException if the rule is not defined in the grammar
     */
    public Pattern compile(String ruleName) {
        String regex = getRegex(ruleName.toLowerCase(Locale.ROOT), new LinkedHashSet<>());
        return Pattern.compile(regex);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Grammar extraction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract the content of the first {@code ```abnf ... ```} fenced code block.
     */
    static String extractAbnfBlock(String markdown) {
        Matcher m = Pattern.compile("```abnf\\n(.*?)```", Pattern.DOTALL).matcher(markdown);
        if (!m.find()) {
            throw new IllegalArgumentException("No ```abnf fenced code block found in Markdown");
        }
        return m.group(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rule parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse ABNF rule definitions from raw ABNF text.
     *
     * <p>Returns a map of lower-cased rule names to their rule-body text
     * (continuation lines joined with a space, comments stripped).
     */
    static Map<String, String> parseRules(String abnf) {
        Map<String, String> rules = new LinkedHashMap<>();
        String currentName = null;
        StringBuilder currentBody = new StringBuilder();

        for (String rawLine : abnf.split("\n", -1)) {
            String line = stripComment(rawLine);

            // New rule definition starts at column 0 with a name and '='
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                int eqPos = indexOfEquals(line);
                if (eqPos > 0) {
                    // Flush previous rule
                    flushRule(rules, currentName, currentBody);

                    String name = line.substring(0, eqPos).trim().toLowerCase(Locale.ROOT);
                    boolean incremental = eqPos + 1 < line.length()
                            && line.charAt(eqPos + 1) == '/';
                    int bodyStart = incremental ? eqPos + 2 : eqPos + 1;
                    String fragment = line.substring(bodyStart).trim();

                    if (incremental && rules.containsKey(name)) {
                        // "=/" – append as an alternative to the existing rule
                        currentName = name;
                        String existing = rules.remove(name);
                        currentBody = new StringBuilder(existing);
                        if (!fragment.isEmpty()) {
                            currentBody.append(" / ").append(fragment);
                        }
                    } else {
                        currentName = name;
                        currentBody = new StringBuilder(fragment);
                    }
                    continue;
                }
            }

            // Continuation line (starts with whitespace or is blank)
            if (currentName != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    if (currentBody.length() > 0) {
                        currentBody.append(' ');
                    }
                    currentBody.append(trimmed);
                }
            }
        }

        // Flush the last rule
        flushRule(rules, currentName, currentBody);
        return rules;
    }

    private static void flushRule(Map<String, String> rules, String name, StringBuilder body) {
        if (name != null) {
            String b = body.toString().trim();
            if (!b.isEmpty()) {
                rules.put(name, b);
            }
        }
    }

    /** Return the index of the first {@code =} not inside a double-quoted string. */
    private static int indexOfEquals(String s) {
        boolean inQ = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQ = !inQ;
            } else if (!inQ && c == '=') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Strip everything from {@code ;} to the end of the line,
     * unless the semicolon is inside a double-quoted string.
     */
    static String stripComment(String line) {
        boolean inQ = false;
        int i;
        for (i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQ = !inQ;
            } else if (!inQ && c == ';') {
                break;
            }
        }
        return line.substring(0, i);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regex compilation – entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Return the compiled regex string for {@code name}, compiling it on demand.
     *
     * @param name  lower-cased rule name
     * @param stack rules currently being compiled (cycle detection)
     */
    private String getRegex(String name, Set<String> stack) {
        if (regexCache.containsKey(name)) {
            return regexCache.get(name);
        }
        String body = ruleTexts.get(name);
        if (body == null) {
            throw new NoSuchElementException("Unknown ABNF rule: '" + name + "'");
        }
        if (!stack.add(name)) {
            throw new IllegalStateException("Circular rule reference: " + stack + " → " + name);
        }
        int[] pos = {0};
        String regex = parseAlt(body, pos, stack);

        // Verify that the entire body was consumed
        skipWS(body, pos);
        if (pos[0] != body.length()) {
            throw new IllegalStateException(
                    "Rule '" + name + "': trailing garbage at position " + pos[0]
                    + ": '" + body.substring(pos[0]) + "'  (full body: '" + body + "')");
        }

        stack.remove(name);
        regexCache.put(name, regex);
        return regex;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recursive-descent parser for ABNF rule bodies
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * alternation = concatenation *( "/" concatenation )
     */
    private String parseAlt(String s, int[] p, Set<String> stack) {
        List<String> alts = new ArrayList<>();
        alts.add(parseConcat(s, p, stack));
        while (peekChar(s, p) == '/') {
            p[0]++;       // consume '/'
            skipWS(s, p);
            alts.add(parseConcat(s, p, stack));
        }
        if (alts.size() == 1) {
            return alts.get(0);
        }
        return "(?:" + String.join("|", alts) + ")";
    }

    /**
     * concatenation = *(WSP repetition WSP)
     * Stops when it sees '/', ')', or ']'.
     */
    private String parseConcat(String s, int[] p, Set<String> stack) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            skipWS(s, p);
            char c = peekChar(s, p);
            if (c == 0 || c == '/' || c == ')' || c == ']') {
                break;
            }
            sb.append(parseRep(s, p, stack));
        }
        return sb.toString();
    }

    /**
     * repetition = [min] "*" [max] element  |  element
     */
    private String parseRep(String s, int[] p, Set<String> stack) {
        skipWS(s, p);
        int save = p[0];
        int minVal = tryInt(s, p);          // -1 = none
        boolean hasStar = peekChar(s, p) == '*';

        if (hasStar) {
            p[0]++;                         // consume '*'
            int maxVal = tryInt(s, p);      // -1 = unbounded
            String elem = wrapForQuantifier(parseElem(s, p, stack));
            int lo = Math.max(0, minVal);
            if (lo == 0 && maxVal < 0) {
                return elem + "*";
            }
            if (lo == 1 && maxVal < 0) {
                return elem + "+";
            }
            String loStr = String.valueOf(lo);
            String hiStr = (maxVal < 0) ? "" : String.valueOf(maxVal);
            return elem + "{" + loStr + "," + hiStr + "}";
        }

        // No '*' – restore and parse plain element
        p[0] = save;
        return parseElem(s, p, stack);
    }

    /**
     * element = rulename | "(" alternation ")" | "[" alternation "]"
     *         | char-val | num-val
     */
    private String parseElem(String s, int[] p, Set<String> stack) {
        skipWS(s, p);
        char c = peekChar(s, p);
        if (c == 0) {
            throw new IllegalStateException("Unexpected end of expression: '" + s + "'");
        }

        if (c == '"') {
            return parseCharVal(s, p);
        }
        if (c == '%') {
            return parseNumVal(s, p);
        }
        if (c == '[') {
            p[0]++;
            skipWS(s, p);
            String inner = parseAlt(s, p, stack);
            skipWS(s, p);
            expect(s, p, ']');
            return "(?:" + inner + ")?";
        }
        if (c == '(') {
            p[0]++;
            skipWS(s, p);
            String inner = parseAlt(s, p, stack);
            skipWS(s, p);
            expect(s, p, ')');
            return "(?:" + inner + ")";
        }
        if (Character.isLetter(c)) {
            return parseRuleRef(s, p, stack);
        }
        throw new IllegalStateException(
                "Unexpected character '" + c + "' at position " + p[0]
                + " in rule body: '" + s + "'");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Terminal parsers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * char-val = DQUOTE *(%x20-21 / %x23-7E) DQUOTE
     * Terminal strings are case-insensitive in ABNF (RFC 5234 §2.3).
     */
    private static String parseCharVal(String s, int[] p) {
        expect(s, p, '"');
        int start = p[0];
        while (p[0] < s.length() && s.charAt(p[0]) != '"') {
            p[0]++;
        }
        String lit = s.substring(start, p[0]);
        expect(s, p, '"');
        return buildLiteralRegex(lit);
    }

    /**
     * num-val = "%" "x" hexDigits ( "-" hexDigits | ("." hexDigits)* )
     * Only hex values ({@code %x}) are used in the PURL grammar.
     */
    private static String parseNumVal(String s, int[] p) {
        expect(s, p, '%');
        char type = nextChar(s, p);
        if (type != 'x' && type != 'X') {
            throw new IllegalStateException(
                    "Only %%x values are supported (got '%%" + type + "')");
        }
        int first = readHexInt(s, p);

        if (peekChar(s, p) == '-') {
            // Range: %xHH-HH → character class [...]
            p[0]++;
            int last = readHexInt(s, p);
            return "[" + hexCharInClass(first) + "-" + hexCharInClass(last) + "]";
        }

        // Single value or concatenation (%xHH.HH.HH)
        StringBuilder sb = new StringBuilder();
        sb.append(hexChar(first));
        while (peekChar(s, p) == '.') {
            p[0]++;
            sb.append(hexChar(readHexInt(s, p)));
        }
        return sb.toString();
    }

    /**
     * Rule reference: parse a name ([A-Za-z][A-Za-z0-9-]*) and look it up.
     */
    private String parseRuleRef(String s, int[] p, Set<String> stack) {
        int start = p[0];
        while (p[0] < s.length()
                && (Character.isLetterOrDigit(s.charAt(p[0])) || s.charAt(p[0]) == '-')) {
            p[0]++;
        }
        String name = s.substring(start, p[0]).toLowerCase(Locale.ROOT);
        return getRegex(name, stack);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Literal / hex helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a case-insensitive regex for an ABNF terminal string.
     * Regex meta-characters are escaped individually.
     */
    private static String buildLiteralRegex(String lit) {
        if (lit.isEmpty()) {
            return "";
        }
        // Escape regex meta-characters (outside a character class)
        StringBuilder sb = new StringBuilder();
        boolean hasAlpha = false;
        for (char c : lit.toCharArray()) {
            if (Character.isLetter(c)) {
                hasAlpha = true;
            }
            // Characters that are special in Java regex (outside character classes)
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        String escaped = sb.toString();
        // RFC 5234 §2.3: terminal strings are case-insensitive by default
        return hasAlpha ? "(?i:" + escaped + ")" : escaped;
    }

    /**
     * Convert a codepoint to a Java regex atom matching exactly that character.
     * Uses {@code \xHH} for U+0000–U+00FF.
     */
    private static String hexChar(int cp) {
        if (cp > 0xFF) {
            return String.format("\\u%04X", cp);
        }
        return String.format("\\x%02X", cp);
    }

    /**
     * Variant of {@link #hexChar} safe to use inside a character class {@code [...]}.
     * (Same encoding works in both positions for the PURL grammar's ASCII range.)
     */
    private static String hexCharInClass(int cp) {
        return hexChar(cp);
    }

    /**
     * Wrap the regex in a non-capturing group if necessary so that a quantifier
     * ({@code *}, {@code +}, {@code {n,m}}) is applied to the whole expression.
     */
    private static String wrapForQuantifier(String regex) {
        if (regex.length() == 1) {
            return regex;
        }
        // Already a single atom that accepts a quantifier directly:
        if (regex.startsWith("(?") && endsWithCloseParen(regex)) {
            return regex;
        }
        if (regex.startsWith("[") && regex.endsWith("]")) {
            return regex;
        }
        if ((regex.startsWith("\\x") || regex.startsWith("\\u"))
                && regex.length() <= 6) {
            return regex;
        }
        return "(?:" + regex + ")";
    }

    /** True when {@code s} ends with a {@code )} that closes the first {@code (}. */
    private static boolean endsWithCloseParen(String s) {
        if (!s.endsWith(")")) return false;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i == s.length() - 1;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Low-level string scanning helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static char peekChar(String s, int[] p) {
        return (p[0] < s.length()) ? s.charAt(p[0]) : 0;
    }

    private static char nextChar(String s, int[] p) {
        if (p[0] >= s.length()) {
            throw new IllegalStateException("Unexpected end of input");
        }
        return s.charAt(p[0]++);
    }

    private static void skipWS(String s, int[] p) {
        while (p[0] < s.length()
                && (s.charAt(p[0]) == ' ' || s.charAt(p[0]) == '\t')) {
            p[0]++;
        }
    }

    private static void expect(String s, int[] p, char expected) {
        if (peekChar(s, p) != expected) {
            throw new IllegalStateException(
                    "Expected '" + expected + "' at position " + p[0]
                    + " in: '" + s + "'");
        }
        p[0]++;
    }

    /**
     * Try to parse a non-negative integer at the current position.
     *
     * @return parsed integer, or {@code -1} if no digit was found
     */
    private static int tryInt(String s, int[] p) {
        if (p[0] >= s.length() || !Character.isDigit(s.charAt(p[0]))) {
            return -1;
        }
        int start = p[0];
        while (p[0] < s.length() && Character.isDigit(s.charAt(p[0]))) {
            p[0]++;
        }
        return Integer.parseInt(s.substring(start, p[0]));
    }

    private static int readHexInt(String s, int[] p) {
        int start = p[0];
        while (p[0] < s.length() && isHexDigit(s.charAt(p[0]))) {
            p[0]++;
        }
        if (p[0] == start) {
            throw new IllegalStateException(
                    "Expected hex digits at position " + p[0] + " in: '" + s + "'");
        }
        return Integer.parseInt(s.substring(start, p[0]), 16);
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
