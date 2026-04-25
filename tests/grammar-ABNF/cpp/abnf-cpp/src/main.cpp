// PURL ABNF Grammar Validation Tests
// Ecosystem: C++ / abnf-cpp (self-contained ABNF parser)
//
// Extracts the ABNF grammar from docs/standard/grammar.md, then validates
// the string values found in JSON test suites under tests/ against the
// ABNF rules `purl` and `purl-canonical`.
//
// Usage:
//   purl-grammar-tests <grammar.md> <tests-dir>
//   purl-grammar-tests                        (auto-discovers paths from CWD)

#include <algorithm>
#include <cassert>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#include <nlohmann/json.hpp>

namespace fs = std::filesystem;
using json   = nlohmann::json;

// ============================================================
//  ABNF Node
// ============================================================

struct AbnfNode {
    enum Type {
        LITERAL,    // "text"  – case-insensitive string match
        CHAR_RANGE, // %xNN-MM  (single char when lo == hi)
        CHAR_SEQ,   // %xNN.MM.KK  – concatenated specific bytes
        RULE_REF,   // rule-name
        SEQ,        // a b c
        ALT,        // a / b / c
        REP         // *a  n*m a
    };
    Type type{LITERAL};

    // LITERAL
    std::string literal;

    // CHAR_RANGE
    uint8_t range_lo{0}, range_hi{0};

    // CHAR_SEQ
    std::vector<uint8_t> bytes;

    // RULE_REF
    std::string rule_name;

    // SEQ / ALT
    std::vector<std::shared_ptr<AbnfNode>> children;

    // REP
    std::shared_ptr<AbnfNode> rep_child;
    int min_rep{1}, max_rep{1}; // max_rep == UNLIMITED means unbounded

    static constexpr int UNLIMITED = -1;
};

using NodePtr = std::shared_ptr<AbnfNode>;

// ---- factory helpers ----

static NodePtr make_literal(const std::string& s)
{
    auto n    = std::make_shared<AbnfNode>();
    n->type   = AbnfNode::LITERAL;
    n->literal = s;
    return n;
}

static NodePtr make_range(uint8_t lo, uint8_t hi)
{
    auto n       = std::make_shared<AbnfNode>();
    n->type      = AbnfNode::CHAR_RANGE;
    n->range_lo  = lo;
    n->range_hi  = hi;
    return n;
}

static NodePtr make_charseq(const std::vector<uint8_t>& b)
{
    auto n    = std::make_shared<AbnfNode>();
    n->type   = AbnfNode::CHAR_SEQ;
    n->bytes  = b;
    return n;
}

static NodePtr make_ruleref(std::string name)
{
    std::transform(name.begin(), name.end(), name.begin(), ::tolower);
    auto n        = std::make_shared<AbnfNode>();
    n->type       = AbnfNode::RULE_REF;
    n->rule_name  = name;
    return n;
}

static NodePtr make_seq(std::vector<NodePtr> ch)
{
    if (ch.size() == 1) return ch[0];
    auto n       = std::make_shared<AbnfNode>();
    n->type      = AbnfNode::SEQ;
    n->children  = std::move(ch);
    return n;
}

static NodePtr make_alt(std::vector<NodePtr> ch)
{
    if (ch.size() == 1) return ch[0];
    auto n       = std::make_shared<AbnfNode>();
    n->type      = AbnfNode::ALT;
    n->children  = std::move(ch);
    return n;
}

static NodePtr make_rep(NodePtr child, int mn, int mx)
{
    auto n        = std::make_shared<AbnfNode>();
    n->type       = AbnfNode::REP;
    n->rep_child  = std::move(child);
    n->min_rep    = mn;
    n->max_rep    = mx;
    return n;
}

// ============================================================
//  ABNF expression parser
// ============================================================

class ExprParser {
public:
    explicit ExprParser(std::string t) : text_(std::move(t)), pos_(0) {}

    NodePtr parse()
    {
        auto r = parse_alternation();
        skip_ws();
        if (pos_ < text_.size())
            throw std::runtime_error(
                "Trailing text in ABNF expr at pos " + std::to_string(pos_)
                + ": \"" + text_.substr(pos_, 20) + "\"");
        return r;
    }

private:
    std::string text_;
    size_t      pos_;

    char peek(size_t off = 0) const
    {
        return (pos_ + off < text_.size()) ? text_[pos_ + off] : '\0';
    }
    char consume() { return text_[pos_++]; }
    bool try_consume(char c)
    {
        if (pos_ < text_.size() && text_[pos_] == c) { ++pos_; return true; }
        return false;
    }
    void skip_ws()
    {
        while (pos_ < text_.size() && std::isspace((unsigned char)text_[pos_]))
            ++pos_;
    }

    // alternation = concatenation *("/" concatenation)
    NodePtr parse_alternation()
    {
        std::vector<NodePtr> alts;
        alts.push_back(parse_concatenation());
        while (true) {
            skip_ws();
            if (peek() == '/') {
                ++pos_;
                skip_ws();
                alts.push_back(parse_concatenation());
            } else {
                break;
            }
        }
        return make_alt(std::move(alts));
    }

    // concatenation = *repetition  (stops at end / / ) ])
    NodePtr parse_concatenation()
    {
        std::vector<NodePtr> items;
        while (true) {
            skip_ws();
            char c = peek();
            if (c == '\0' || c == '/' || c == ')' || c == ']') break;
            items.push_back(parse_repetition());
        }
        if (items.empty()) return make_literal(""); // epsilon
        return make_seq(std::move(items));
    }

    // repetition = [repeat] element
    // repeat     = *DIGIT ["*" *DIGIT]  /  "*" [*DIGIT]
    NodePtr parse_repetition()
    {
        skip_ws();
        int mn = 1, mx = 1;

        if (peek() == '*') {
            ++pos_;
            mn = 0; mx = AbnfNode::UNLIMITED;
        } else if (std::isdigit((unsigned char)peek())) {
            std::string d;
            while (std::isdigit((unsigned char)peek())) d += consume();
            if (peek() == '*') {
                ++pos_;
                mn = std::stoi(d);
                std::string d2;
                while (std::isdigit((unsigned char)peek())) d2 += consume();
                mx = d2.empty() ? AbnfNode::UNLIMITED : std::stoi(d2);
            } else {
                mn = mx = std::stoi(d); // exact repetition
            }
        }

        NodePtr elem = parse_element();
        if (mn == 1 && mx == 1) return elem;
        return make_rep(std::move(elem), mn, mx);
    }

    // element = rulename / group / option / char-val / num-val
    NodePtr parse_element()
    {
        skip_ws();
        char c = peek();
        if (c == '"')  return parse_char_val();
        if (c == '%')  return parse_num_val();
        if (c == '(')  return parse_group();
        if (c == '[')  return parse_option();
        if (std::isalpha((unsigned char)c) || c == '_') {
            std::string name;
            while (pos_ < text_.size()
                   && (std::isalpha((unsigned char)text_[pos_])
                       || std::isdigit((unsigned char)text_[pos_])
                       || text_[pos_] == '-'
                       || text_[pos_] == '_'))
                name += consume();
            return make_ruleref(name);
        }
        throw std::runtime_error(
            std::string("Unexpected char in ABNF element: '") + c
            + "' at pos " + std::to_string(pos_));
    }

    NodePtr parse_group()
    {
        if (!try_consume('('))
            throw std::runtime_error("Expected '('");
        skip_ws();
        auto inner = parse_alternation();
        skip_ws();
        if (!try_consume(')'))
            throw std::runtime_error("Expected ')'");
        return inner;
    }

    NodePtr parse_option()
    {
        if (!try_consume('['))
            throw std::runtime_error("Expected '['");
        skip_ws();
        auto inner = parse_alternation();
        skip_ws();
        if (!try_consume(']'))
            throw std::runtime_error("Expected ']'");
        return make_rep(std::move(inner), 0, 1);
    }

    // char-val = DQUOTE *char DQUOTE  (case-insensitive)
    NodePtr parse_char_val()
    {
        if (!try_consume('"'))
            throw std::runtime_error("Expected '\"'");
        std::string s;
        while (pos_ < text_.size() && text_[pos_] != '"') s += consume();
        if (!try_consume('"'))
            throw std::runtime_error("Expected closing '\"'");
        return make_literal(s);
    }

    // num-val = "%" ("x" / "d") ...
    NodePtr parse_num_val()
    {
        if (!try_consume('%'))
            throw std::runtime_error("Expected '%'");
        char base = peek();
        if (base == 'x' || base == 'X') { ++pos_; return parse_hex_val(); }
        if (base == 'd' || base == 'D') { ++pos_; return parse_dec_val(); }
        throw std::runtime_error(
            std::string("Unsupported num-val base: '") + base + "'");
    }

    uint32_t read_hex()
    {
        std::string s;
        while (pos_ < text_.size()
               && std::isxdigit((unsigned char)text_[pos_]))
            s += consume();
        if (s.empty()) throw std::runtime_error("Expected hex digits");
        return static_cast<uint32_t>(std::stoul(s, nullptr, 16));
    }

    uint32_t read_dec()
    {
        std::string s;
        while (pos_ < text_.size()
               && std::isdigit((unsigned char)text_[pos_]))
            s += consume();
        if (s.empty()) throw std::runtime_error("Expected decimal digits");
        return static_cast<uint32_t>(std::stoul(s));
    }

    // %xNN  /  %xNN-MM  /  %xNN.MM.KK
    NodePtr parse_hex_val()
    {
        uint32_t first = read_hex();
        if (peek() == '-') {
            ++pos_;
            return make_range(static_cast<uint8_t>(first),
                              static_cast<uint8_t>(read_hex()));
        }
        if (peek() == '.') {
            std::vector<uint8_t> bv{ static_cast<uint8_t>(first) };
            while (peek() == '.') { ++pos_; bv.push_back(static_cast<uint8_t>(read_hex())); }
            return make_charseq(bv);
        }
        return make_range(static_cast<uint8_t>(first),
                          static_cast<uint8_t>(first));
    }

    NodePtr parse_dec_val()
    {
        uint32_t first = read_dec();
        if (peek() == '-') {
            ++pos_;
            return make_range(static_cast<uint8_t>(first),
                              static_cast<uint8_t>(read_dec()));
        }
        if (peek() == '.') {
            std::vector<uint8_t> bv{ static_cast<uint8_t>(first) };
            while (peek() == '.') { ++pos_; bv.push_back(static_cast<uint8_t>(read_dec())); }
            return make_charseq(bv);
        }
        return make_range(static_cast<uint8_t>(first),
                          static_cast<uint8_t>(first));
    }
};

// ============================================================
//  Grammar (rule map) + parser
// ============================================================

using Grammar = std::map<std::string, NodePtr>;

static std::string normalize_rule_name(std::string s)
{
    while (!s.empty() && std::isspace((unsigned char)s.front())) s.erase(s.begin());
    while (!s.empty() && std::isspace((unsigned char)s.back()))  s.pop_back();
    std::transform(s.begin(), s.end(), s.begin(), ::tolower);
    return s;
}

// Remove everything from the first ';' that is outside a string literal.
static std::string strip_comment(const std::string& s)
{
    bool in_str = false;
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '"')       in_str = !in_str;
        else if (!in_str && s[i] == ';') return s.substr(0, i);
    }
    return s;
}

static Grammar parse_grammar(const std::string& abnf_text)
{
    Grammar grammar;
    std::istringstream iss(abnf_text);
    std::string line, cur_name, cur_expr;

    auto save = [&]() {
        if (cur_name.empty()) return;
        auto key = normalize_rule_name(cur_name);
        try {
            ExprParser ep(cur_expr);
            grammar[key] = ep.parse();
        } catch (const std::exception& e) {
            std::cerr << "Warning: could not parse rule '" << key
                      << "': " << e.what() << "\n"
                      << "  expr: " << cur_expr << "\n";
        }
        cur_name.clear();
        cur_expr.clear();
    };

    while (std::getline(iss, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back(); // CRLF
        if (line.empty()) continue;

        if (line[0] == ';') continue; // full-line comment

        if (std::isalpha((unsigned char)line[0])) {
            // New rule
            save();
            auto eq = line.find('=');
            if (eq == std::string::npos) continue;
            cur_name = line.substr(0, eq);
            cur_expr = strip_comment(line.substr(eq + 1));
        } else if (std::isspace((unsigned char)line[0]) && !cur_name.empty()) {
            // Continuation
            cur_expr += ' ' + strip_comment(line);
        }
    }
    save();
    return grammar;
}

// Prepend RFC 5234 core rules (only those not already defined).
static void add_core_rules(Grammar& grammar)
{
    static const char* core_abnf = R"(
ALPHA   = %x41-5A / %x61-7A
DIGIT   = %x30-39
HEXDIG  = DIGIT / "A" / "B" / "C" / "D" / "E" / "F"
WSP     = %x20 / %x09
SP      = %x20
HTAB    = %x09
CR      = %x0D
LF      = %x0A
CRLF    = CR LF
VCHAR   = %x21-7E
OCTET   = %x00-FF
)";
    auto core = parse_grammar(core_abnf);
    for (auto& [k, v] : core)
        grammar.emplace(k, v); // emplace: does not overwrite existing
}

// ============================================================
//  Matcher  (set-based position tracking = implicit backtracking)
// ============================================================

using PosSet = std::set<size_t>;

struct MemoKey {
    std::string rule;
    size_t      pos;
    bool operator==(const MemoKey& o) const { return pos == o.pos && rule == o.rule; }
};
struct MemoHash {
    size_t operator()(const MemoKey& k) const
    {
        return std::hash<std::string>{}(k.rule)
               ^ (std::hash<size_t>{}(k.pos) * 2654435761UL);
    }
};
using Memo = std::unordered_map<MemoKey, PosSet, MemoHash>;

static PosSet match_node(const NodePtr& node, const std::string& input,
                         const PosSet& starts, const Grammar& grammar,
                         Memo& memo);

static PosSet match_rule(const std::string& name, size_t start_pos,
                         const std::string& input, const Grammar& grammar,
                         Memo& memo)
{
    MemoKey key{name, start_pos};
    auto it = memo.find(key);
    if (it != memo.end()) return it->second;

    // Insert empty set first to handle indirect left-recursion gracefully.
    memo[key] = {};

    auto rit = grammar.find(name);
    if (rit == grammar.end())
        throw std::runtime_error("Unknown ABNF rule: " + name);

    PosSet result = match_node(rit->second, input, {start_pos}, grammar, memo);
    memo[key]     = result;
    return result;
}

static PosSet match_node(const NodePtr& node, const std::string& input,
                         const PosSet& starts, const Grammar& grammar,
                         Memo& memo)
{
    if (starts.empty()) return {};

    switch (node->type) {

    case AbnfNode::LITERAL: {
        const auto& lit = node->literal;
        PosSet result;
        for (size_t pos : starts) {
            if (pos + lit.size() > input.size()) continue;
            bool ok = true;
            for (size_t i = 0; i < lit.size(); ++i) {
                if (std::tolower((unsigned char)input[pos + i])
                    != std::tolower((unsigned char)lit[i]))
                { ok = false; break; }
            }
            if (ok) result.insert(pos + lit.size());
        }
        return result;
    }

    case AbnfNode::CHAR_RANGE: {
        PosSet result;
        for (size_t pos : starts) {
            if (pos >= input.size()) continue;
            uint8_t c = static_cast<uint8_t>(input[pos]);
            if (c >= node->range_lo && c <= node->range_hi)
                result.insert(pos + 1);
        }
        return result;
    }

    case AbnfNode::CHAR_SEQ: {
        const auto& bv = node->bytes;
        PosSet result;
        for (size_t pos : starts) {
            if (pos + bv.size() > input.size()) continue;
            bool ok = true;
            for (size_t i = 0; i < bv.size(); ++i)
                if (static_cast<uint8_t>(input[pos + i]) != bv[i]) { ok = false; break; }
            if (ok) result.insert(pos + bv.size());
        }
        return result;
    }

    case AbnfNode::RULE_REF: {
        PosSet result;
        for (size_t pos : starts) {
            auto ends = match_rule(node->rule_name, pos, input, grammar, memo);
            result.insert(ends.begin(), ends.end());
        }
        return result;
    }

    case AbnfNode::SEQ: {
        PosSet cur = starts;
        for (const auto& child : node->children) {
            cur = match_node(child, input, cur, grammar, memo);
            if (cur.empty()) return {};
        }
        return cur;
    }

    case AbnfNode::ALT: {
        PosSet result;
        for (const auto& child : node->children) {
            auto ends = match_node(child, input, starts, grammar, memo);
            result.insert(ends.begin(), ends.end());
        }
        return result;
    }

    case AbnfNode::REP: {
        const auto& child = node->rep_child;
        int mn = node->min_rep;
        int mx = node->max_rep; // AbnfNode::UNLIMITED = unbounded

        // Match exactly mn times first.
        PosSet cur = starts;
        for (int i = 0; i < mn; ++i) {
            cur = match_node(child, input, cur, grammar, memo);
            if (cur.empty()) return {};
        }

        // cur now holds all positions after exactly mn matches.
        PosSet result = cur;

        // Match up to (mx - mn) additional times.
        // For unbounded (*), cap at input.size()+1: a repetition can advance
        // by at most one position per step, so more iterations than that can
        // never produce new positions. This avoids INT_MAX iteration counts.
        const int input_len = static_cast<int>(input.size());
        int extra = (mx == AbnfNode::UNLIMITED) ? input_len + 1 : mx - mn;
        for (int i = 0; i < extra && !cur.empty(); ++i) {
            PosSet nxt = match_node(child, input, cur, grammar, memo);
            // Only keep newly-discovered positions to ensure termination.
            PosSet frontier;
            for (size_t p : nxt) {
                if (result.insert(p).second) // inserted = new position
                    frontier.insert(p);
            }
            if (frontier.empty()) break;
            cur = frontier;
        }
        return result;
    }

    default:
        throw std::runtime_error("Unknown AbnfNode type");
    }
}

static bool validate(const Grammar& grammar, const std::string& rule,
                     const std::string& input)
{
    std::string norm = rule;
    std::transform(norm.begin(), norm.end(), norm.begin(), ::tolower);
    Memo memo;
    auto ends = match_rule(norm, 0, input, grammar, memo);
    return ends.count(input.size()) > 0;
}

// ============================================================
//  Extract ABNF block from Markdown
// ============================================================

static std::string extract_abnf_from_markdown(const std::string& md)
{
    std::istringstream iss(md);
    std::string line, result;
    bool in_block = false;
    while (std::getline(iss, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (!in_block) {
            if (line.rfind("```abnf", 0) == 0) in_block = true;
        } else {
            if (line.rfind("```", 0) == 0) break;
            result += line + '\n';
        }
    }
    if (result.empty())
        throw std::runtime_error("No ```abnf block found in grammar.md");
    return result;
}

// ============================================================
//  File helpers
// ============================================================

static std::string read_file(const fs::path& p)
{
    std::ifstream f(p);
    if (!f) throw std::runtime_error("Cannot open: " + p.string());
    return { std::istreambuf_iterator<char>(f),
             std::istreambuf_iterator<char>() };
}

// ============================================================
//  Test runner
// ============================================================

struct TestResult {
    std::string name;
    bool        passed{false};
    std::string message;
};

// Keep test-name characters readable but unambiguous.
static std::string sanitize(const std::string& s)
{
    std::string r;
    r.reserve(s.size());
    for (unsigned char c : s) {
        if (std::isalnum(c) || c == '.' || c == '-' || c == '_' || c == ':'
            || c == '@' || c == '/' || c == '?' || c == '=' || c == '&'
            || c == '#' || c == '+' || c == '~')
            r += static_cast<char>(c);
        else
            r += '_';
    }
    return r;
}

static std::vector<TestResult> run_suite(
    const Grammar& grammar,
    const fs::path& json_path,
    const fs::path& tests_root)
{
    std::vector<TestResult> results;

    json suite;
    try {
        suite = json::parse(read_file(json_path));
    } catch (const std::exception& e) {
        std::cerr << "Warning: could not parse " << json_path << ": " << e.what() << '\n';
        return results;
    }

    if (!suite.contains("tests") || !suite["tests"].is_array()) return results;

    // Determine name components:
    //   grammar.<folder>.<file-base>.input.<value>
    //   grammar.<folder>.<file-base>.expected_output.<value>
    auto rel       = fs::relative(json_path, tests_root);
    auto it        = rel.begin();
    std::string folder = (std::next(it) != rel.end()) ? it->string() : ".";
    std::string base   = json_path.stem().string();
    std::string prefix = "grammar." + folder + "." + base;

    // Per-value counters to disambiguate duplicate values within one suite.
    std::map<std::string, int> input_seen, output_seen;

    for (const auto& test : suite["tests"]) {
        bool expected_failure =
            test.contains("expected_failure")
            && test["expected_failure"].is_boolean()
            && test["expected_failure"].get<bool>();

        // ---- input validation ----
        if (test.contains("input") && test["input"].is_string()) {
            const std::string val = test["input"].get<std::string>();

            int& cnt = input_seen[val];
            std::string name = prefix + ".input." + sanitize(val)
                               + (cnt > 0 ? ("." + std::to_string(cnt)) : "");
            ++cnt;

            bool matches = validate(grammar, "purl", val);

            TestResult tr;
            tr.name   = name;
            tr.passed = expected_failure ? !matches : matches;
            if (!tr.passed)
                tr.message = std::string("ABNF rule `purl`: expected ")
                             + (expected_failure ? "FAIL" : "PASS")
                             + " but got "
                             + (matches ? "PASS" : "FAIL")
                             + " for: " + val;
            results.push_back(std::move(tr));
        }

        // ---- expected_output validation (skip when expected_failure) ----
        if (!expected_failure
            && test.contains("expected_output")
            && test["expected_output"].is_string())
        {
            const std::string val = test["expected_output"].get<std::string>();

            int& cnt = output_seen[val];
            std::string name = prefix + ".expected_output." + sanitize(val)
                               + (cnt > 0 ? ("." + std::to_string(cnt)) : "");
            ++cnt;

            bool matches = validate(grammar, "purl-canonical", val);

            TestResult tr;
            tr.name   = name;
            tr.passed = matches;
            if (!tr.passed)
                tr.message = "ABNF rule `purl-canonical`: expected PASS"
                             " but got FAIL for: " + val;
            results.push_back(std::move(tr));
        }
    }

    return results;
}

// ============================================================
//  main
// ============================================================

int main(int argc, char* argv[])
{
    fs::path grammar_path, tests_dir;

    if (argc >= 3) {
        grammar_path = argv[1];
        tests_dir    = argv[2];
    } else {
        // Auto-discover: walk up from CWD looking for docs/standard/grammar.md
        for (fs::path p = fs::current_path();
             p.has_parent_path() && p != p.parent_path();
             p = p.parent_path())
        {
            auto g = p / "docs" / "standard" / "grammar.md";
            if (fs::exists(g)) {
                grammar_path = g;
                tests_dir    = p / "tests";
                break;
            }
        }
    }

    if (!fs::exists(grammar_path)) {
        std::cerr << "Error: grammar.md not found.\n"
                  << "Usage: " << argv[0] << " <grammar.md> <tests-dir>\n";
        return 2;
    }
    if (!fs::exists(tests_dir)) {
        std::cerr << "Error: tests directory not found: " << tests_dir << '\n';
        return 2;
    }

    std::cout << "Grammar : " << grammar_path << '\n';
    std::cout << "Tests   : " << tests_dir    << '\n' << '\n';

    // ---- Load grammar ----
    Grammar grammar;
    try {
        auto md   = read_file(grammar_path);
        auto abnf = extract_abnf_from_markdown(md);
        grammar   = parse_grammar(abnf);
        add_core_rules(grammar);
        std::cout << "Loaded " << grammar.size() << " ABNF rules.\n\n";
    } catch (const std::exception& e) {
        std::cerr << "Error loading grammar: " << e.what() << '\n';
        return 2;
    }

    // ---- Collect JSON test files ----
    std::vector<fs::path> json_files;
    for (const auto& entry : fs::recursive_directory_iterator(tests_dir)) {
        if (entry.is_regular_file() && entry.path().extension() == ".json")
            json_files.push_back(entry.path());
    }
    std::sort(json_files.begin(), json_files.end());

    // ---- Run tests ----
    std::vector<TestResult> all;
    for (const auto& jf : json_files) {
        auto suite_results = run_suite(grammar, jf, tests_dir);
        for (auto& r : suite_results) all.push_back(std::move(r));
    }

    // ---- Report ----
    int passed = 0, failed = 0;
    for (const auto& r : all) {
        if (r.passed) {
            std::cout << "PASS  " << r.name << '\n';
            ++passed;
        } else {
            std::cout << "FAIL  " << r.name << '\n';
            if (!r.message.empty())
                std::cout << "      " << r.message << '\n';
            ++failed;
        }
    }

    std::cout << "\n=== " << (passed + failed) << " tests: "
              << passed << " passed, " << failed << " failed ===\n";

    return (failed > 0) ? 1 : 0;
}
