var o = {
  get a() {
    return 1;
  },
  set a(v) {
    this._a = v;
  },
  get "quoted"() {
    return 2;
  },
  get 3() {
    return 4;
  }
};
