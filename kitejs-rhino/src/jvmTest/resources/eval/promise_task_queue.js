// A queue that runs a fixed number of jobs at a time, built only on promises.
function makeQueue(limit) {
  var running = 0, waiting = [], log = [];
  function pump() {
    while (running < limit && waiting.length > 0) {
      var job = waiting.shift();
      running++;
      log.push('start ' + job.name);
      job.run().then(function (value) {
        running--;
        log.push('done ' + job.name + '=' + value);
        job.settle(value);
        pump();
      }, function (reason) {
        running--;
        log.push('fail ' + job.name + '=' + reason);
        job.fail(reason);
        pump();
      });
    }
  }
  return {
    add: function (name, run) {
      var settle, fail;
      var p = new Promise(function (res, rej) { settle = res; fail = rej; });
      waiting.push({ name: name, run: run, settle: settle, fail: fail });
      pump();
      return p;
    },
    log: log
  };
}

var q = makeQueue(2);
var results = [];
function immediate(v) { return function () { return Promise.resolve(v); }; }
function broken(e) { return function () { return Promise.reject(e); }; }

q.add('a', immediate(1)).then(function (v) { results.push('a:' + v); });
q.add('b', immediate(2)).then(function (v) { results.push('b:' + v); });
q.add('c', immediate(3)).then(function (v) { results.push('c:' + v); });
q.add('d', broken('x')).catch(function (e) { results.push('d!' + e); });

// A second queue that keeps the ordering of a chain built in a loop.
var chain = Promise.resolve(0);
var chainLog = [];
for (var i = 1; i <= 4; i++) {
  (function (n) {
    chain = chain.then(function (acc) { chainLog.push(n); return acc + n; });
  })(i);
}
var total = null;
chain.then(function (v) { total = v; });

// Nothing above has run yet: promises never call back before the turn ends.
var beforeDrain = [q.log.length, results.length, chainLog.length, total];

Promise.resolve().then(function () {
  // Still not everything, but enough to show the ordering is real.
}).then(function () {}).then(function () {
  finish();
});

var out = [];
function finish() {
  out.push(q.log.join(','));
  out.push(results.join(','));
  out.push(chainLog.join(','));
  out.push(String(total));
}

globalThis.result = function () {
  return [beforeDrain.join(':'), q.log.join(','), results.join(','), chainLog.join(','), String(total)].join('#');
};
'queued';

// AFTER: globalThis.result()
