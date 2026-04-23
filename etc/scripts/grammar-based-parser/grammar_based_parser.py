from abnf.parser import Rule, ParseError, Node
import glob
import json

class PurlParser(Rule):
    pass
    

def make_parser():
    grammar_location = "docs/standard/grammar.md"
    grammar_text = ""
    with open(grammar_location, "r") as grammar_file:
        grammar_text = grammar_file.read()
        grammar_text = grammar_text[grammar_text.index("```abnf")+7:]
        grammar_text = grammar_text[:grammar_text.index("```")]
        grammar_text = grammar_text.strip()
        #print(grammar_text)

    with open("tmp.grammar", "w") as just_grammar:
        just_grammar.write(grammar_text)
    PurlParser.from_file("tmp.grammar")
    return PurlParser("purl")
def load_tests():
    test_location = "tests/spec/specification-test.json"
    test_list = []
    with open(test_location, "r") as test_file:
        test_list=json.load(test_file)["tests"]
    return test_list
def map_name_to_node(root: Node, current_map: dict): #note currentMap is an outparam
    current_map[root.name] = root
    for child in root.children:
        map_name_to_node(child, current_map)

def load_schemes():
    types_dir = "types"
    rules_files = glob.glob("types/*.json")
    # todo


def process_result(root: Node) -> bool:
    node_map = {}
    map_name_to_node(root, node_map)
    print(node_map)
    return True

purl_parser = make_parser()
for test in load_tests():
    success = True
    print(test["input"])
    try:
        result = purl_parser.parse_all(test["input"])
        print(result)
        process_result(result)
    except ParseError as e:
        success = False
    except Exception as e:
        print("other exception: "+str(e))
        success=False
    if test["expected_failure"]:
        continue

    if test["expected_failure"] == success:
        print("~~~~~~~~~~~~~~~~~~~~\ntest did not pass: \n\n"+ json.dumps(test, indent=True))
    else:
        print("~~~~~~~~~~~~~~~~~~~~\ntest passed: \n\n"+ json.dumps(test, indent=True))
    

