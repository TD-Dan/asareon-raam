package app.auf.fakes

import app.auf.service.SourceCodeService
import app.auf.util.PlatformDependencies

class FakeSourceCodeService(platform: PlatformDependencies) : SourceCodeService(platform) {
    var collateCalled = false
    var nextResult = "// Fake source code" // MODIFIED: Changed default to reflect typical source code

    override fun collateKtFilesToString(): String {
        collateCalled = true
        return nextResult
    }
}