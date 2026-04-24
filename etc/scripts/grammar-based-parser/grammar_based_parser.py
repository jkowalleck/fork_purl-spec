from abnf.parser import Rule, ParseError, Node
import glob
import json

#run this with python after installing the requirements. 
# working directory is expected to be the top level of the project

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
def map_name_to_node(root: Node, node_map: dict, value_map: dict): #note currentMap is an outparam
    node_map[root.name] = root
    # todo: maybe do something a bit more clever for the qualifiers, which can be multiple
    value_map[root.name] = root.value
    for child in root.children:
        map_name_to_node(child, node_map, value_map)

def load_schemes():
    schemes = {}
    types_dir = "types"
    rules_files = glob.glob("types/*.json")
    for filename in rules_files:
        with open(filename, "r") as file:
            scheme = json.load(file)
            schemes[scheme["type"]] = scheme
    return schemes

def validate_against_schemes(node_map: dict, value_map: dict, schemes: list):
    scheme = schemes[value_map["type"]]
    print("validating : "+value_map["purl"]+" against: "+scheme["type"])
    if(scheme["repository"]["use_repository"] !=("repository" in value_map)):
        print("expected value for repository presence was wrong")
        return False
    if(scheme["name_definition"]["requirement"]=="required"):
        if("name" not in value_map):
            print("expected value for name, and found none")
            return False
    if(scheme["namespace_definition"]["requirement"]=="required"):
        pass # todo
    # we assume qualifiers are optional
    provided_qualifiers = []
    if("qualifiers" in node_map):
        # validate we are only passed expected qualifiers
        expected_quals = []
        for qual in  scheme["qualifiers_definition"]:
            expected_quals.append(qual["key"])
        qual_root: Node = node_map.get("qualifiers")
        for qual_child in qual_root.children:
            for qual_grandchild in qual_child.children:
                if(qual_grandchild.name == "qualifier-key"):
                    provided_qualifiers.append(qual_grandchild.value)
        for qual in provided_qualifiers:
            if qual not in expected_quals:
                print("qualifier unexpected: "+qual)
                return False
    print("!!!!!!!!successfully passed validation!!!!!!!!!!!!")
    return True

def process_parse_result(root: Node, schemes: list) -> bool:
    node_map = {}
    value_map = {}
    map_name_to_node(root, node_map, value_map)
    return validate_against_schemes(node_map, value_map, schemes)

purl_parser = make_parser()
schemes = load_schemes()
for test in load_tests():
    print("\n\n\n~~~~~Preparing for test~~~~~~\n"+json.dumps(test, indent=True))
    test_input = ""
    if test["test_type"] == "build":
        test_input = test["expected_output"]
        if(test_input is None):
            print("\nno purl to validate here, moving on")
            continue
    else:
        test_input = test["input"]
    if isinstance(test_input, dict):
        print("there is a dict for test_input: "+str(test_input)+" skipping test. ")
        continue
    print("~~~~~~~~~~Beginning Test~~~~~~\npurl to validate: "+str(test_input)+"\n---")
    success = True
    result: Node = None
    try:
        result = purl_parser.parse_all(test_input)
    except ParseError as e:
        print("failed to parse the pURL - parse error: "+str(e))
        success = False
    except Exception as e:
        print("other exception: "+str(e))
        print("this is unexpected, therefore, this test has failed due to an error with the verifier")
        print("~~~~~~~~End Test~~~~~~~~~~~")
        continue
    if success:
        success = process_parse_result(result, schemes)

    if test["expected_failure"] == success:
        print("test did not pass: "+ test_input)
    else:
        print("test passed: "+ test_input)
    print("~~~~~~~~End Test~~~~~~~~~~~")

