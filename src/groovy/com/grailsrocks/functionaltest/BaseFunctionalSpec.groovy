package com.grailsrocks.functionaltest

import com.grailsrocks.functionaltest.client.BrowserClient
import com.grailsrocks.functionaltest.client.Client
import com.grailsrocks.functionaltest.client.ClientAdapter
import com.grailsrocks.functionaltest.client.ContentChangedEvent
import com.grailsrocks.functionaltest.util.HTTPUtils
import groovy.util.slurpersupport.GPathResult
import junit.framework.AssertionFailedError
import org.codehaus.groovy.grails.web.json.JSONElement
import org.codehaus.groovy.runtime.InvokerHelper
import org.codehaus.groovy.runtime.StackTraceUtils
import spock.lang.Specification

import static org.junit.Assert.*

/**
 * Created by saagrawal on 6/18/14.
 */
abstract class BaseFunctionalSpec extends Specification implements GroovyInterceptable, ClientAdapter {

    static MONKEYING_DONE

    static BORING_STACK_ITEMS = [
            'FunctionalTests',
            'functionaltestplugin.',
            'gant.',
            'com.gargoylesoftware',
            'org.apache']

    static {
        StackTraceUtils.addClassTest { className ->
            if (BORING_STACK_ITEMS.find { item ->
                return className.startsWith(item)
            }) {
                return false
            } else {
                return null
            }
        }
    }

    static int maxW = 80

    def baseURL // populated via test script
    def urlStack = new ArrayList()
    boolean autoFollowRedirects = true
    def consoleOutput
    protected stashedClients = [:]
    private String currentClientId
    Client currentClient
    def redirectUrl
    def authHeader

    def contentTypeForJSON = 'application/json'
    def contentTypeForXML = 'text/xml'

    def setup() {
        baseURL = System.getProperty('grails.functional.test.baseURL')

        if (!MONKEYING_DONE) {
            BrowserClient.initVirtualMethods(this)
            MONKEYING_DONE = true
        }

        if (!consoleOutput) {
            consoleOutput = System.out
        }
    }

    abstract Class getDefaultClientType()

    Client getClient() {
        if (!currentClient) {
            client('default')
            clientChanged()
        }
        return currentClient
    }

    protected void clientChanged() {
        currentClient?.clientChanged()
    }

    boolean isRedirectEnabled() {
        autoFollowRedirects
    }

    void setRedirectEnabled(boolean enabled) {
        autoFollowRedirects = enabled
    }

    boolean __isDSLMethod(String name) {
        name.startsWith('assert') ||
                name.startsWith('shouldFail') ||
                name.startsWith('fail') ||
                name == 'client' ||
                name == 'defaultClientType'
    }

    /**
     * Call to switch between multiple clients, simulating different users or access scenarios (REST API + browser)
     */
    void client(String id, Class<Client> type = getDefaultClientType()) {
        printlnToTestReport "Switching to browser client [$id]"
        if (id != currentClientId) {
            // If we were currently unnamed but have some state, save our state with name ""
            stashClient(currentClientId ?: '')
            currentClient = null
            // restore client if it is known, else
            unstashClient(id)
            if (!currentClient) {
                // Creat new
                printlnToTestReport "Creating to new client [$id] of type [$type]"
                currentClient = (type ?: BrowserClient).newInstance(this)
                stashClient(id)
            }
        }
        currentClientId = id
    }

    String getCurrentClientId() {
        this.@currentClientId
    }

    protected void stashClient(id) {
        stashedClients[id] = currentClient
    }

    protected void unstashClient(id) {
        // Clear them in case this is a new unknown client name
        def c = stashedClients[id]
        if (c) {
            currentClient = c
        }

        clientChanged()
    }

    protected void cleanup() {
        currentClient = null
        printlnToConsole '' // force newline
    }

    PrintStream getInteractiveOut() {
        System.out
    }

    PrintStream getTestReportOut() {
        System.out
    }

    def invokeMethod(String name, args) {
        def t = this
        // Let's not mess with internal calls, or it is a nightmare to debug
        if (!name.startsWith('__') && __isDSLMethod(name)) {
            try {
                return InvokerHelper.getMetaClass(this).invokeMethod(this, name, args)
            } catch (Throwable e) {
                // Protect against nested func test exceptions when one assertX calls another
                if (!(e instanceof FunctionalTestException)) {
                    __reportFailure(__sanitize(e))
                    throw __sanitize(new FunctionalTestException(this, e))
                } else throw e
            }
        } else {
            return InvokerHelper.getMetaClass(this).invokeMethod(this, name, args)
        }
    }

    protected __sanitize(Throwable t) {
        StackTraceUtils.deepSanitize(t)
    }

    protected void __reportFailure(e) {
        // Write out to user console
        def msg
        if (!e.message) {
            msg = "[no message available]"
        } else {
            msg = e.message
        }
        def out = consoleOutput ?: System.out
        out.println "\nFailed: ${msg}"
        e.printStackTrace(out)
        if (e.cause) {
            out.println "\nFailed: ${msg}"
            e.cause.printStackTrace(out)
        }
        // Write to output capture file
        //println "\nFailed: ${msg}"
        if (urlStack) {
            out.println "URL: ${urlStack[-1].url}"
        }
        out.println ""
    }

    void followRedirect() {
        if (redirectEnabled) {
            throw new IllegalStateException("Trying to followRedirect() but you have not disabled automatic redirects so I can't! Do redirectEnabled = false first, then call followRedirect() after asserting.")
        }
        doFollowRedirect()
    }

    protected void doFollowRedirect() {
        def u = redirectUrl
        if (u) {
            get(u) // @todo should be same HTTP method as previous request?
            printlnToTestReport "Followed redirect to $u"
        } else {
            throw new IllegalStateException('The last response was not a redirect, so cannot followRedirect')
        }
    }

    def forceTrailingSlash(url) {
        if (!url.endsWith('/')) {
            url += '/'
        }
        return url
    }

    URL makeRequestURL(url) {
        def reqURL
        url = url.toString()
        if ((url.indexOf('://') >= 0) || url.startsWith('file:')) {
            reqURL = url.toURL()
        } else {
            def base
            if (url.startsWith('/')) {
                base = forceTrailingSlash(baseURL)
                url -= '/'
            } else {
                base = client.currentURL ? client.currentURL : baseURL
            }
            reqURL = new URL(new URL(base), url.toString())
        }
        return reqURL
    }

    protected handleRedirects() {
        if (HTTPUtils.isRedirectStatus(client.responseStatus)) {
            if (autoFollowRedirects) {
                this.doFollowRedirect()
            }
        }
    }

    def doRequest(String url, String method, Closure paramSetup = null) {
        // @todo build URL like we used to, relative to the app:
        URL u = makeRequestURL(url)

        redirectUrl = null
        client.request(u, method, paramSetup)
    }

    def get(url, Closure paramSetup = null) {
        doRequest(url, 'GET', paramSetup)
    }

    def post(url, Closure paramSetup = null) {
        doRequest(url, 'POST', paramSetup)
    }

    def delete(url, Closure paramSetup = null) {
        doRequest(url, 'DELETE', paramSetup)
    }

    def put(url, Closure paramSetup = null) {
        doRequest(url, 'PUT', paramSetup)
    }

    void assertContentDoesNotContain(String expected) {
        assertFalse "Expected content to not loosely contain [$expected] but it did".toString(), stripWS(client.responseAsString?.toLowerCase()).contains(stripWS(expected?.toLowerCase()))
    }

    void assertContentContains(String expected) {
        assertTrue "Expected content to loosely contain [$expected] but it didn't".toString(), stripWS(client.responseAsString?.toLowerCase()).contains(stripWS(expected?.toLowerCase()))
    }

    void assertContentContainsStrict(String expected) {
        assertTrue "Expected content to strictly contain [$expected] but it didn't".toString(), client.responseAsString?.contains(expected)
    }

    void assertContent(String expected) {
        assertEquals stripWS(expected?.toLowerCase()), stripWS(client.responseAsString?.toLowerCase())
    }

    void assertContentStrict(String expected) {
        assertEquals expected, client.responseAsString
    }

    void expect(Map args) {
        if (args.status) {
            assertStatus(args.status)
        }
        if (args.contentType) {
            assertContentType(args.contentType)
        }
        if (args.contentTypeStrict) {
            assertContentTypeString(args.contentTypeStrict)
        }
        if (args.redirectUrl) {
            assertRedirectUrl(args.redirectUrl)
        }
        if (args.redirectUrlContains) {
            assertRedirectUrlContains(args.redirectUrlContains)
        }
        if (args.content) {
            assertContent(args.content)
        }
        if (args.contentStrict) {
            assertContentStrict(args.contentStrict)
        }
        if (args.contentContains) {
            assertContentContains(args.contentContains)
        }
        if (args.contentContainsStrict) {
            assertContentContainsStrict(args.contentContainsStrict)
        }
    }

    void assertStatus(int status) {
        def msg = "Expected HTTP status [$status] but was [${client.responseStatus}]"
        if (HTTPUtils.isRedirectStatus(client.responseStatus)) msg += " (received a redirect to ${redirectUrl})"
        assertTrue msg.toString(), status == client.responseStatus
    }

    void assertRedirectUrl(String expected) {
        if (redirectEnabled) {
            throw new IllegalStateException("Asserting redirect, but you have not disabled redirects. Do redirectEnabled = false first, then call followRedirect() after asserting.")
        }
        if (!HTTPUtils.isRedirectStatus(client.responseStatus)) {
            throw new AssertionFailedError("Asserting redirect, but response was not a valid redirect status code")
        }
        assertEquals expected, redirectUrl
    }

    void assertRedirectUrlContains(String expected) {
        if (redirectEnabled) {
            throw new IllegalStateException("Asserting redirect, but you have not disabled redirects. Do redirectEnabled = false first, then call followRedirect() after asserting.")
        }
        if (!HTTPUtils.isRedirectStatus(client.responseStatus)) {
            throw new AssertionFailedError("Asserting redirect, but response was not a valid redirect status code")
        }
        if (!redirectUrl?.contains(expected)) {
            throw new AssertionFailedError("Asserting redirect contains [$expected], but it didn't. Was: [${redirectUrl}]")
        }
    }

    void assertContentTypeStrict(String expected) {
        assertEquals expected, client.responseContentType
    }

    void assertContentType(String expected) {
        def respType = stripWS(client.responseContentType.toLowerCase())
        assertTrue "Expected content type to match [${expected}] but it was [${respType}]", respType.startsWith(stripWS(expected?.toLowerCase()))
    }

    void assertHeader(String header, String expected) {
        assertEquals stripWS(expected.toLowerCase()), stripWS(client.getResponseHeader(header)?.toLowerCase())
    }

    void assertHeaderStrict(String header, String expected) {
        assertEquals expected, client.getResponseHeader(header)
    }

    void assertHeaderContains(String header, String expected) {
        def respHeader = stripWS(client.getResponseHeader(header)?.toLowerCase())
        assertTrue "Expected header [$header] to match [${expected}] but it was [${respHeader}]", respHeader.contains(stripWS(expected?.toLowerCase()))
    }

    void assertHeaderContainsStrict(String header, String expected) {
        def respHeader = stripWSclient.getResponseHeader(header)
        assertTrue "Expected header [$header] to strictly match [${expected}] but it was [${respHeader}]", respHeader?.contains(expected)
    }


    JSONElement getJSON() {
        assertContentType contentTypeForJSON
        grails.converters.JSON.parse(client.responseAsString)
    }

    GPathResult getXML() {
        assertContentType contentTypeForXML
        grails.converters.XML.parse(client.responseAsString)
    }

    String getContent() {
        client?.responseAsString
    }

    String getContentType() {
        client?.responseContentType
    }

    void accept(String types) {
        client.setStickyHeader('Accept', types)
    }

    /**
     * Set the Authorization header
     */
    void authBasic(String user, String pass) {
        printlnToTestReport "Authentication set to: Basic $user:$pass"
        client.setAuth('Basic', user, pass)
    }

    void authHeader(String header, String value) {
        printlnToTestReport "Authorization header set to ${header}: ${value}"
        client.setStickyHeader(header, value)
        authHeader = header
    }

    /**
     * Set the Authorization header
     */
    void clearAuth() {
        client.clearAuth()
        if (authHeader) {
            client.clearStickyHeader(authHeader)
            authHeader = null
        }
    }

    /**
     * Load a fixture into the app using the fixtures plugin
     */
    void fixture(String name) {
        def result = testDataRequest('fixture', [name: name])
        if (result.error) {
            throw new UnsupportedOperationException("Cannot load fixture [$name], the application replied with: [{}$result.error}]")
        }
    }

    def URLEncode(x) {
        URLEncoder.encode(x.toString(), 'utf-8')
    }

    /**
     * Send a request to the test data controller that this plugin injects into non-production apps
     * @param action The name of the controller action to execute eg findObject
     * @param params The query args
     * @return The JSON response object
     */
    def testDataRequest(action, params) {
        def args = (params.collect { k, v -> k + '=' + URLEncode(v) }).join('&')
        grails.converters.JSON.parse(makeRequestURL("/functionaltesting/$action?$args").text)
    }

    /**
     * Assert that the mock mail system has a mail matching the specified args
     */
    void assertEmailSent(Map args) {
        try {
            def result = makeRequestURL('/greenmail/list').text
            result = result?.toLowerCase()
            if (result.indexOf(args.to.toLowerCase()) < 0 || result.indexOf(args.subject?.toLowerCase()) < 0) {
                throw new AssertionFailedError("There was no email to an address containing [$args.to] with subject containing [$args.subject] found - greenmail had the following: ${result}")
            }
        } catch (FileNotFoundException fnfe) {
            throw new UnsupportedOperationException("Cannot interact with mocked mails, the application does not have the 'greenmail' plugin installed or url mapping for /greenmail/\$action? is missing")
        }
    }

    /**
     * Clear the greenmail email queue.
     * @todo should do this after every test run from the test runner
     */
    void clearEmails() {
        def result = makeRequestURL('/greenmail/clear').text
    }

    /**
     * Extract the first match from the contentAsString using the supplied regex
     */
    String extract(regexPattern) {
        def m = client.responseAsString =~ regexPattern
        return m ? m[0][1] : null
    }

/*
	void assertXML(String xpathExpr, expectedValue) {

	}
*/

    String stripWS(String s) {
        def r = new StringBuffer()
        s?.each { c ->
            if (!Character.isWhitespace(c.toCharacter())) r << c
        }
        r.toString()
    }

    protected newResponseReceived(Client client) {
        if (HTTPUtils.isRedirectStatus(client.responseStatus)) {
            redirectUrl = client.getResponseHeader('Location')
            printlnToTestReport("Response was a redirect to ${redirectUrl} ${'<' * 20}")
        } else {
            redirectUrl = null
        }
        dumpResponseHeaders(client)
        dumpContent(client)

        // Now let's see if it was a redirect
        handleRedirects()
    }

    /**
     * Called by client implementations when new response received
     */
    void contentChanged(ContentChangedEvent event) {
        newResponseReceived(event.client)

        // params.method ? params.method.toString()+' ' :
        consoleOutput.print '#'
        while (urlStack.size() >= 50) { // only keep a window of the last 50 urls
            urlStack.remove(0)
        }
        urlStack << event
    }

    void requestSent(Client client) {
        dumpRequestInfo(client)
    }

    void printlnToConsole(String s) {
        consoleOutput.println s
    }

    void printlnToTestReport(String s) {
        testReportOut.println s
    }

    void dumpHeading(String title) {
        def padL = '== '
        def padR = '=' * Math.max((int) 2, (int) (maxW - (padL.length() + 1 + title.length())))

        testReportOut.println(padL + title + ' ' + padR)
    }

    void dumpSeparator() {
        testReportOut.println('=' * maxW)
    }

    void dumpRequestInfo(Client client) {
        testReportOut.println('')
        dumpHeading("Making request ${client.requestMethod} ${client.currentURL} parameters:")
        client?.requestParameters?.each {
            testReportOut.println("${it.key}: ${it.value}")
        }
        dumpHeading("Request headers:")
        client?.requestHeaders?.each { Map.Entry it ->
            testReportOut.println("${it.key}: ${it.value}")
        }
        dumpHeading("Content")
        testReportOut.println(client.requestBody)
        dumpSeparator()
    }

    void dumpResponseHeaders(Client client) {
        dumpHeading("Response was ${client.responseStatus} (${client.responseStatusMessage ?: 'no message'}) headers:")
        client?.responseHeaders?.each {
            testReportOut.println("${it.key}: ${it.value}")
        }
        dumpSeparator()
    }

    void dumpContent(Client client) {
        dumpHeading("Content")
        testReportOut.println(client.responseAsString)
        dumpSeparator()
    }


}
