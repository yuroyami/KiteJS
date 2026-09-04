var log = [];
function CustomError(message, code) {
  this.name = 'CustomError';
  this.message = message;
  this.code = code;
}
CustomError.prototype = Object.create(Error.prototype);
CustomError.prototype.constructor = CustomError;
function ValidationError(field) {
  var e = Error.call(this, 'bad ' + field);
  this.message = e.message;
  this.name = 'ValidationError';
  this.field = field;
}
ValidationError.prototype = Object.create(Error.prototype);
ValidationError.prototype.constructor = ValidationError;
function order() {
  try { log.push('try'); throw new Error('boom'); }
  catch (e) { log.push('catch:' + e.message); return 'from catch'; }
  finally { log.push('finally'); }
}
function override() { try { return 'try'; } finally { return 'finally'; } }
function nested() {
  try {
    try { throw new CustomError('inner', 42); }
    finally { log.push('inner finally'); }
  } catch (e) { return e.name + ':' + e.code + ':' + (e instanceof Error) + ':' + (e instanceof CustomError); }
}
function rethrow() {
  try { try { throw 'first'; } catch (e) { throw e + '+second'; } } catch (e) { return e; }
}
function loopFinally() {
  var r = '';
  for (var i = 0; i < 3; i++) { try { if (i === 1) continue; if (i === 2) break; r += i; } finally { r += 'f'; } }
  return r;
}
var out = [order(), log.join(), override(), nested(), rethrow(), loopFinally()];
try { throw new ValidationError('email'); } catch (e) { out.push(e.name, e.message, e.field, e instanceof ValidationError, e instanceof Error, '' + e); }
try { null.x; } catch (e) { out.push(e.name, e instanceof TypeError); }
try { undefinedFunction(); } catch (e) { out.push(e.name, e.message); }
try { throw { custom: true }; } catch (e) { out.push(typeof e, e.custom); }
try { throw 42; } catch (e) { out.push(e + 1); }
var caught = 0;
try { try { throw 1; } finally { caught++; } } catch (e) { caught += 10; }
out.push(caught);
out.push((function () { try { throw new RangeError('r'); } catch ({ name, message }) { return name + '/' + message; } })());
var err = new Error('with props', { cause: 'root' });
out.push(err.cause, Object.keys(err).join(), err.propertyIsEnumerable('message'), JSON.stringify(err));
out.join('|');
