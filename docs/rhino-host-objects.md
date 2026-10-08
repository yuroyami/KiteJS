# Rhino host objects

Every Rhino object implements `Scriptable`. `Reflect` and the corresponding `Object` methods
accept those objects regardless of whether their storage comes from `ScriptableObject`.

Use `ScriptableObject` when the engine should manage properties, attributes and accessors.
Implement `Scriptable` directly when properties live in a host data structure. The original
`get`, `put`, `has`, `delete`, prototype and scope methods remain sufficient for a legacy host.
The additional object operations have default implementations, including JVM interface defaults.

| Operation | Legacy default | Override when |
|---|---|---|
| `ownPropertyKeys()` | The names returned by `getIds()` | The host has non-enumerable keys, symbols or a different own-key order |
| `getOwnPropertyDescriptor(cx, key)` | An own data property, writable, enumerable and configurable | Properties have accessors or different attributes |
| `defineOwnPropertyOrFalse(cx, key, descriptor)` | Writes representable data definitions; refuses unsupported attributes and accessors | The host can retain other descriptors or report mutation refusal directly |
| `isExtensible` | `true` | The host can prevent new properties |
| `preventExtensions()` | `false` | The host can enforce non-extensibility on subsequent writes and definitions |
| `setPrototypeOf(cx, prototype)` | Checks extensibility and ordinary prototype cycles, then sets the prototype | The host has an immutable or otherwise exotic prototype |

Keys use the engine's existing string, integer-index and `Symbol` representation. Implement
`SymbolScriptable` when the value operations support symbols. Own-key operations include
non-enumerable properties and preserve the host's order. Descriptors contain
`Scriptable.NOT_FOUND` for omitted fields; an explicit `undefined` is a supplied value.

The default interface cannot retain accessor functions or altered property flags beside a
host's own storage. For example, defining a new property with only `{value: 1}` requires false
flags, so a legacy host refuses it. Defining `{value: 1, writable: true, enumerable: true,
configurable: true}` is representable. A value-only update to an existing mutable data property
preserves its attributes. A host that supports richer definitions implements the boolean
operation and reports its actual resulting descriptors.

`Reflect.defineProperty` returns `false` when the definition is refused. `Object.defineProperty`
throws a `TypeError` for that refusal. An exception raised by the host operation propagates
through either method. The pre-existing `ScriptableObject.defineOwnProperty` API keeps its
exception behavior; embedders can use `defineOwnPropertyOrFalse` when they need the boolean
operation.

The same distinction applies to extensibility. A legacy host that cannot prevent writes answers
`false` from `Reflect.preventExtensions` and remains extensible. `Object.preventExtensions`,
`Object.seal` and `Object.freeze` throw if the host refuses the operation. A descriptor-aware
host can support them by enforcing its extensibility and descriptor state consistently.

`Delegator` forwards metadata and mutations to its delegee. The legacy `With` wrapper reports
the wrapped object's property descriptors and keys. Proxies accept host targets and handlers,
forward missing traps through the same object-operation contract, and validate trap answers
against the host's actual descriptors and extensibility.

Ordinary property writes through `Reflect.set` respect the target's descriptor and write to the
receiver. Accessor calls receive that receiver. A host with `Callable` or `Constructable`
capabilities remains an object and can participate in `Reflect.apply`, `Reflect.construct` and
proxy forwarding. Primitive `this` boxing in the Rhino call protocol remains tracked in
[#47](https://github.com/yuroyami/KiteJS/issues/47) and
[#78](https://github.com/yuroyami/KiteJS/issues/78).
