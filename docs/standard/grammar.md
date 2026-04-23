# Package-URL Grammar

A PURL string adheres to the following grammar,
using syntax as per [RFC5234: Augmented BNF for Syntax Specifications: ABNF](https://datatracker.ietf.org/doc/html/rfc5234).

```abnf
purl                      = scheme ":" *"/" type
                            [ 1*"/" namespace           ] 1*"/" name *"/"
                            [ "@" version ] [ "?" qualifiers           ]
                            [ "#" *"/" subpath      *"/" ]
                            ; leading/trailing slashes allowed here and there
purl-canonical            = scheme ":"      type-canonical
                            [   "/" namespace-canonical ]   "/" name
                            [ "@" version ] [ "?" qualifiers-canonical ]
                            [ "#"      subpath-canonical ]


scheme                    = %x70.6B.67    ; lowercase string "pkg"

type                      =    ALPHA *(    ALPHA / DIGIT / "." / "-" )
type-canonical            = LOWALPHA *( LOWALPHA / DIGIT / "." / "-" )

namespace                 = namespace-segment *( 1*"/" namespace-segment )
namespace-canonical       = namespace-segment *(   "/" namespace-segment )
namespace-segment         = 1*namespace-sc
namespace-sc              = PERM-ALPHANUM
                          / PERM-PUNCTUATION
                          / PERM-COLON
                          / "%" ( PERM-ESCAPED-00-1F
                                / PERM-ESCAPED-20-2C
                                ; except punctuation: "-"  (2D)
                                ; except punctuation: "."  (2E)
                                ; except the separator "/" (2F)
                                / PERM-ESCAPED-30-FF )
                            ; namespace safe characters

name                      = 1*PCT-ENCODED

version                   = 1*PCT-ENCODED

qualifiers                = qualifier           *( "&" qualifier           )
qualifiers-canonical      = qualifier-canonical *( "&" qualifier-canonical )
qualifier                 = qualifier-key           "=" [ qualifier-value ]
qualifier-canonical       = qualifier-key-canonical "="   qualifier-value
qualifier-key             =    ALPHA *(    ALPHA / DIGIT / "." / "-" / "_" )
qualifier-key-canonical   = LOWALPHA *( LOWALPHA / DIGIT / "." / "-" / "_" )
qualifier-value           = 1*PCT-ENCODED

subpath                   = [ subpath-segment
                              *( 1*"/" subpath-segment           )
                            ]    ; zero or more segments
subpath-canonical         = [ subpath-segment-canonical
                              *(   "/" subpath-segment-canonical )
                            ]    ; zero or more segments
subpath-segment           =                   1*( subpath-sc / "." / "%2E" )
subpath-segment-canonical = [ "." ] subpath-sc *( subpath-sc / "." )
                            ; prevent "." and ".." standalone
                          / "." "."           1*( subpath-sc / "." )
                            ; prevent ".." standalone
subpath-sc                = PERM-ALPHANUM
                          / "-" / "_" / "~"  ; PERM-PUNCTUATION except "."
                          / PERM-COLON
                          / "%" ( PERM-ESCAPED-00-1F
                                / PERM-ESCAPED-20-2C
                                ; except punctuation: "-"     (2D)
                                ; except the special char "." (2E)
                                ; except the separator "/"    (2F)
                                / PERM-ESCAPED-30-FF )
                            ; subpath safe characters


UPRALPHA    = %x41-5A    ; A-Z
LOWALPHA    = %x61-7A    ; a-z

PCT-ENCODED = PERM-ALPHANUM
            / PERM-PUNCTUATION
            / PERM-COLON    ; a specific separator that must not be encoded
            / PERM-ESCAPED

; permitted character classes
PERM-ALPHANUM    = ALPHA / DIGIT
PERM-PUNCTUATION = "." / "-" / "_" / "~"
PERM-SEPARATOR   = ":" / "/" / "@" / "?" / "=" / "&" / "#"
PERM-COLON       = ":"
PERM-ESCAPED     = "%" ( PERM-ESCAPED-00-1F
                       / PERM-ESCAPED-20-2C )
PERM-SPACE       = "%20"    ; escaped space

; applied purl spec rules for general character encoding
PERM-ESCAPED-00-1F = %x30-31                               HEXDIG    ; 00-1F
PERM-ESCAPED-20-2C = %x32             ( DIGIT / "A" / "B" / "C" )    ; 20-2C
```
