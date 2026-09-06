// A proxy that enforces a schema on writes and reports the first bad one.
function typed(schema) {
  return new Proxy({}, {
    set: function (t, key, value) {
      var want = schema[key];
      if (want === undefined) throw new TypeError('unknown field ' + String(key));
      if (typeof value !== want) throw new TypeError(String(key) + ' wants ' + want + ', got ' + typeof value);
      t[key] = value;
      return true;
    },
    get: function (t, key) {
      return key in t ? t[key] : null;
    }
  });
}

var user = typed({ name: 'string', age: 'number', active: 'boolean' });
var errors = [];

function tryWrite(fn) {
  try {
    fn();
    return 'ok';
  } catch (e) {
    errors.push(e.name + ': ' + e.message);
    return 'failed';
  }
}

var r1 = tryWrite(function () { user.name = 'ada'; });
var r2 = tryWrite(function () { user.age = 'thirty'; });
var r3 = tryWrite(function () { user.age = 36; });
var r4 = tryWrite(function () { user.nickname = 'a'; });
var r5 = tryWrite(function () { user.active = true; });

var missing = String(user.missingField);
var summary = [user.name, user.age, user.active].join('/');

[r1, r2, r3, r4, r5, missing, summary, errors.join(' | ')].join('|');
