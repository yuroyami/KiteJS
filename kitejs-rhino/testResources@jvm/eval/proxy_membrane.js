// Wrapping an object graph so that every object reached through it is wrapped too.
function wrap(value, log, path) {
  if (value === null || typeof value !== 'object') return value;
  return new Proxy(value, {
    get: function (t, key) {
      var here = path === '' ? String(key) : path + '.' + String(key);
      var v = t[key];
      if (typeof v === 'function') return v.bind(t);
      log.push(here);
      return wrap(v, log, here);
    }
  });
}

var data = {
  user: { name: 'ada', address: { city: 'london', zip: 'N1' } },
  scores: [3, 1, 2],
  active: true
};

var log = [];
var m = wrap(data, log, '');

var city = m.user.address.city;
var name = m.user.name;
var firstScore = m.scores[0];
var active = m.active;
var sortedScores = m.scores.slice().sort().join(',');

// The log shows every step of the walk, in the order it happened.
var trail = log.join(' ');
var visited = log.length;

// Writes were never trapped, so they land on the real object.
m.user.name = 'grace';
var afterWrite = data.user.name;

[city, name, firstScore, active, sortedScores, visited, afterWrite, trail].join('|');
