// Retry with backoff, driven by a clock the test controls rather than by real time.
function makeClock() {
  var now = 0, timers = [];
  return {
    now: function () { return now; },
    after: function (delay, fn) { timers.push({ at: now + delay, fn: fn }); },
    advance: function (by) {
      now += by;
      timers.sort(function (a, b) { return a.at - b.at; });
      while (timers.length > 0 && timers[0].at <= now) {
        var t = timers.shift();
        t.fn();
      }
    },
    pending: function () { return timers.length; }
  };
}

var clock = makeClock();
var attempts = [];

function flaky(failTimes) {
  var seen = 0;
  return function () {
    seen++;
    attempts.push(clock.now());
    if (seen <= failTimes) return Promise.reject('fail' + seen);
    return Promise.resolve('ok after ' + seen);
  };
}

function retry(task, tries, baseDelay) {
  return new Promise(function (resolve, reject) {
    var attempt = 0;
    function go() {
      attempt++;
      task().then(resolve, function (reason) {
        if (attempt >= tries) { reject('gave up: ' + reason); return; }
        clock.after(baseDelay * Math.pow(2, attempt - 1), go);
      });
    }
    go();
  });
}

var settled = [];
retry(flaky(2), 5, 10).then(function (v) { settled.push('A ' + v); }, function (e) { settled.push('A! ' + e); });
retry(flaky(9), 3, 5).then(function (v) { settled.push('B ' + v); }, function (e) { settled.push('B! ' + e); });

// Each advance lets the queued retries run. The timers only fire from advance, so the
// whole thing is deterministic.
var steps = [];
function step(by) {
  clock.advance(by);
  steps.push(clock.now() + ':' + clock.pending() + ':' + attempts.length);
}

// Each advance runs the retries that were waiting on it. Chaining the steps through the
// queue keeps the whole thing deterministic.
Promise.resolve()
  .then(function () { step(10); })
  .then(function () { step(20); })
  .then(function () { step(40); })
  .then(function () { step(80); })
  .then(function () { step(160); })
  .then(function () { step(320); });

globalThis.result = function () {
  return [attempts.join(','), settled.sort().join(' / '), steps.join(' ')].join('#');
};
'queued';

// AFTER: globalThis.result()
