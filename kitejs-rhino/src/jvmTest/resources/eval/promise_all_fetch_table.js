// Promise.all and its friends over a table that stands in for network calls.
var TABLE = {
  '/a': { ok: true, body: 'alpha' },
  '/b': { ok: true, body: 'beta' },
  '/c': { ok: false, body: 'not found' },
  '/d': { ok: true, body: 'delta' }
};
function fetchPath(path) {
  var row = TABLE[path];
  if (row === undefined) return Promise.reject('unknown ' + path);
  if (!row.ok) return Promise.reject(path + ': ' + row.body);
  return Promise.resolve(row.body);
}

var out = [];
function record(label) {
  return function (v) {
    out.push(label + '=' + (Array.isArray(v) ? v.join(',') : String(v)));
  };
}
function recordErr(label) {
  return function (e) {
    out.push(label + '!' + (e && e.name === 'AggregateError' ? e.name + '[' + e.errors.join(';') + ']' : String(e)));
  };
}

Promise.all(['/a', '/b'].map(fetchPath)).then(record('all-ok'), recordErr('all-ok'));
Promise.all(['/a', '/c'].map(fetchPath)).then(record('all-bad'), recordErr('all-bad'));
Promise.allSettled(['/a', '/c'].map(fetchPath)).then(function (rs) {
  out.push('settled=' + rs.map(function (r) { return r.status + ':' + (r.status === 'fulfilled' ? r.value : r.reason); }).join(' '));
});
Promise.race(['/b', '/a'].map(fetchPath)).then(record('race'), recordErr('race'));
Promise.any(['/c', '/d'].map(fetchPath)).then(record('any'), recordErr('any'));
Promise.any(['/c', '/zz'].map(fetchPath)).then(record('any-none'), recordErr('any-none'));
Promise.all([]).then(record('all-empty'));
Promise.allSettled([]).then(function (rs) { out.push('settled-empty=' + rs.length); });

// Values that are not promises go through untouched.
Promise.all([1, '/a', fetchPath('/b')]).then(record('mixed'), recordErr('mixed'));

// A chain that maps over the results.
Promise.all(['/a', '/b', '/d'].map(fetchPath))
  .then(function (bodies) { return bodies.map(function (b) { return b.toUpperCase(); }); })
  .then(function (upper) { return upper.join('-'); })
  .then(record('chained'));

globalThis.result = function () { return out.sort().join('|'); };
'queued';

// AFTER: globalThis.result()
