try {
  a();
} catch (e) {
  b();
}
try {
  a();
} finally {
  c();
}
try {
  a();
} catch (e) {
  b();
} finally {
  c();
}
try {
  a();
} catch (e) {
  try {
    b();
  } catch (e2) {
    c();
  }
}
throw new Error("boom");
