// The suite runs whole programs and, when the tree is present, tens of thousands of test262
// cases. Mocha's two second default and Karma's short disconnect windows are nowhere near
// enough for that in a browser. The Node runner sets its own timeout in build.gradle.kts.
config.set({
    client: {
        mocha: {
            timeout: 1800000,
        },
    },
    browserDisconnectTimeout: 120000,
    browserDisconnectTolerance: 3,
    browserNoActivityTimeout: 1800000,
    pingTimeout: 1800000,
    processKillTimeout: 60000,
});
