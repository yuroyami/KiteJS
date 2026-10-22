var Store = (function () {
  var items = [], listeners = [];
  function notify(evt) { listeners.forEach(function (l) { l(evt); }); }
  return {
    add: function (item) { items.push(item); notify('add:' + item); return this; },
    remove: function (item) { var i = items.indexOf(item); if (i >= 0) { items.splice(i, 1); notify('remove:' + item); } return this; },
    list: function () { return items.slice(); },
    on: function (fn) { listeners.push(fn); return function () { listeners.splice(listeners.indexOf(fn), 1); }; },
    size: function () { return items.length; }
  };
})();
var events = [];
var off = Store.on(function (e) { events.push(e); });
Store.add('a').add('b').add('c').remove('b').remove('zz');
off();
Store.add('d');
var Namespace = {};
(function (ns) { ns.util = { twice: function (x) { return x * 2; } }; ns.VERSION = '1.0'; })(Namespace);
[Store.list().join(), Store.size(), events.join(), typeof Store.items, Namespace.util.twice(21), Namespace.VERSION, Object.keys(Store).sort().join()].join('|');
