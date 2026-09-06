// A small change tracker: every write, delete and read goes through a proxy that records it.
function observable(target, log) {
  return new Proxy(target, {
    get: function (t, key) {
      log.push('get ' + String(key));
      return t[key];
    },
    set: function (t, key, value) {
      log.push('set ' + String(key) + '=' + value);
      t[key] = value;
      return true;
    },
    deleteProperty: function (t, key) {
      log.push('del ' + String(key));
      delete t[key];
      return true;
    },
    has: function (t, key) {
      log.push('has ' + String(key));
      return key in t;
    }
  });
}

var log = [];
var state = observable({ count: 0, label: 'start' }, log);

state.count = 1;
state.count = state.count + 1;
state.label = 'middle';
var seenLabel = 'label' in state;
delete state.label;
var goneLabel = 'label' in state;
state.label = 'end';

var trail = log.join(',');
var target = { count: 0 };
var plain = observable(target, []);
plain.count = 5;

// The proxy writes through, so the target itself has changed.
var wroteThrough = target.count;

// Reads that miss still reach the target's prototype chain.
var protoRead = String(state.toString).slice(0, 8);

[state.count, seenLabel, goneLabel, state.label, wroteThrough, protoRead, log.length, trail].join('|');
