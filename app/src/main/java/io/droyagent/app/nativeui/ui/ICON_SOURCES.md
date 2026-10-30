# Native UI icons

The `res/drawable/native_ui_*.xml` vectors preserve the original path geometry of
Google Material Symbols Rounded at weight 400, optical size 24. They were obtained
from the official Google Fonts endpoint:

`https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/ICON/default/24px.svg`

`ICON` is the resource suffix, for example `bar_chart` or `folder`.
The send action now uses the Rounded `arrow_upward` geometry from Google's
`material-design-icons/symbols/web/arrow_upward/materialsymbolsrounded/arrow_upward_24px.svg`.
The vector group shifts the source viewBox origin from (0, -960) to (0, 0).

Material Symbols are Copyright Google LLC and licensed under Apache License 2.0:
https://github.com/google/material-design-icons/blob/master/LICENSE

These assets form one consistent icon family; they are not hand-drawn replacements.
