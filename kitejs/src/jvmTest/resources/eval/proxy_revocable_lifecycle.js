// Handing out a capability that can be taken back later.
function grant(target) {
  var r = Proxy.revocable(target, {
    get: function (t, key) {
      var v = t[key];
      return typeof v === 'function' ? v.bind(t) : v;
    }
  });
  return { view: r.proxy, close: r.revoke };
}

var account = {
  balance: 100,
  deposit: function (n) { this.balance += n; return this.balance; },
  toString: function () { return 'account(' + this.balance + ')'; }
};

var handle = grant(account);
var before = handle.view.balance;
var afterDeposit = handle.view.deposit(50);
var text = String(handle.view);

handle.close();

function afterClose(fn) {
  try {
    return 'value:' + fn();
  } catch (e) {
    return e.name;
  }
}

var readAfter = afterClose(function () { return handle.view.balance; });
var writeAfter = afterClose(function () { handle.view.balance = 0; return 'wrote'; });
var hasAfter = afterClose(function () { return 'balance' in handle.view; });
var keysAfter = afterClose(function () { return Object.keys(handle.view).join(','); });
var deleteAfter = afterClose(function () { delete handle.view.balance; return 'deleted'; });

// Revoking twice is harmless, and the target itself is untouched.
handle.close();
var targetStillFine = account.balance;
var closeKind = typeof handle.close;

[before, afterDeposit, text, readAfter, writeAfter, hasAfter, keysAfter, deleteAfter, targetStillFine, closeKind].join('|');
