var a = {};
var b = {
  x: 1
};
var c = {
  x: 1,
  y: 2
};
var d = {
  "quoted": 1,
  'single': 2,
  3: 4
};
var e = {
  get x() {
    return 1;
  },
  set x(v) {
    this._x = v;
  }
};
var f = {
  method() {
    return 1;
  }
};
var g = {
  nested: {
    deep: {
      value: 1
    }
  }
};
var h = {
  if: 1,
  for: 2,
  var: 3
};
