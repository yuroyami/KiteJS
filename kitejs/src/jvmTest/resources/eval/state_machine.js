function tokenize(src) {
  var tokens = [], i = 0, state = 'start', buf = '';
  function flush(type) { if (buf) tokens.push(type + ':' + buf); buf = ''; }
  while (i <= src.length) {
    var c = i < src.length ? src[i] : '';
    switch (state) {
      case 'start':
        if (c >= '0' && c <= '9') { state = 'number'; continue; }
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) { state = 'ident'; continue; }
        if (c === '"') { state = 'string'; i++; continue; }
        if (c === ' ' || c === '') { i++; continue; }
        tokens.push('op:' + c); i++;
        break;
      case 'number':
        if ((c >= '0' && c <= '9') || c === '.') { buf += c; i++; } else { flush('num'); state = 'start'; }
        break;
      case 'ident':
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) { buf += c; i++; } else { flush('id'); state = 'start'; }
        break;
      case 'string':
        if (c === '"' || c === '') { tokens.push('str:' + buf); buf = ''; state = 'start'; i++; } else { buf += c; i++; }
        break;
    }
    if (c === '' && state === 'start') break;
  }
  return tokens;
}
var traffic = { green: { next: 'yellow', wait: 3 }, yellow: { next: 'red', wait: 1 }, red: { next: 'green', wait: 2 } };
var light = 'red', elapsed = 0, trace = [];
for (var t = 0; t < 10; t++) { trace.push(light[0]); if (++elapsed >= traffic[light].wait) { light = traffic[light].next; elapsed = 0; } }
[tokenize('x1 = 42 + foo("a b") * 3.5').join(' '), trace.join(''), tokenize('').length, tokenize('"unterminated').join()].join('|');
