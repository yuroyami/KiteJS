// A read only window onto an object: reads pass through, every write is refused loudly.
function readOnly(target) {
  function refuse(what) {
    throw new TypeError('cannot ' + what + ' on a read only view');
  }
  return new Proxy(target, {
    set: function (t, key) { refuse('set ' + String(key)); },
    deleteProperty: function (t, key) { refuse('delete ' + String(key)); },
    defineProperty: function (t, key) { refuse('define ' + String(key)); }
  });
}

var config = { host: 'localhost', port: 8080, tags: ['a', 'b'] };
var view = readOnly(config);
var failures = [];

function attempt(label, fn) {
  try {
    fn();
    return label + ':allowed';
  } catch (e) {
    failures.push(e.message);
    return label + ':' + e.name;
  }
}

var readHost = view.host;
var readPort = view.port;
var nested = view.tags.join('-');
var a = attempt('write', function () { view.host = 'evil'; });
var b = attempt('delete', function () { delete view.port; });
var c = attempt('define', function () { Object.defineProperty(view, 'x', { value: 1, configurable: true }); });

// The nested array was never wrapped, so it is still writable.
view.tags.push('c');
var nestedAfter = config.tags.join('-');
var unchanged = config.host + ':' + config.port;
var keys = Object.keys(view).join(',');

[readHost, readPort, nested, a, b, c, nestedAfter, unchanged, keys, failures.length].join('|');
