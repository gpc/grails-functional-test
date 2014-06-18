package com.grailsrocks.functionaltest

import com.grailsrocks.functionaltest.client.APIClient

/**
 * Created by saagrawal on 6/18/14.
 */
class APIFunctionalSpec extends BaseFunctionalSpec{

    Class getDefaultClientType() {
        APIClient
    }

    def head(url, Closure paramSetup = null) {
        doRequest(url, 'HEAD', paramSetup)
    }
}
