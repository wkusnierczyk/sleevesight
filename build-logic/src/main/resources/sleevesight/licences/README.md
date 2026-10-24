# Reference licence texts

The texts `listLibraryLicences` holds each notice to (`ListLibraryLicences.kt`, #488): a notice must
contain its licence's whole text here, whitespace, typographic quotes and rules of `=` or `-` aside.
They are the bodies of SPDX's texts (https://github.com/spdx/license-list-data, `text/<id>.txt`),
from the grant to the end, without the title and the copyright line, which vary. Where licences in
use vary in their wording, the text has a placeholder:

- `{*}`: any words, up to 200 characters (BSD-3-Clause's "Neither the name of {*} nor").
- `{-}`: a list item's marker or none (`1.`, `*`, `-`, `(a)`), with its space.
- `{A|B}`: one of the words (BSD-3-Clause's "COPYRIGHT {HOLDER|OWNER}").
