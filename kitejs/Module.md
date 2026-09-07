# Module kitejs

The engine and the API for embedding it. Build a `KiteJs`, bind what a script may reach, run the
script, read the answer.

Start at `io.github.yuroyami.kitejs.api`. The rest of the package tree is the engine itself: the
lexer, the parser, the interpreter and the ECMAScript runtime. You only need those to extend the
engine, not to use it.
