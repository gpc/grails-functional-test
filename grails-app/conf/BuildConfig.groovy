// Add XERCES & XALAN to classpath for building - we assume you're building with 1.1 or higher now
/*
def xmlJars = new File("${basedir}/lib").listFiles().findAll { it.name.endsWith("._jar") }

grailsSettings.compileDependencies.addAll xmlJars
grailsSettings.runtimeDependencies.addAll xmlJars
grailsSettings.testDependencies.addAll xmlJars
*/

grails.project.work.dir = 'target'

forkConfig = false
grails.project.fork = [
        test   : forkConfig, // configure settings for the test-app JVM
        run    : forkConfig, // configure settings for the run-app JVM
        war    : forkConfig, // configure settings for the run-war JVM
        console: forkConfig, // configure settings for the Swing console JVM
        compile: forkConfig  // configure settings for compilation
]

grails.project.dependency.resolver = "maven"
grails.project.dependency.resolution = {
    // inherit Grails' default dependencies
    inherits("global") {
        // specify dependency exclusions here; for example, uncomment this to disable ehcache:
        // excludes 'ehcache'
        //Grails ships with 4.2.5
        //excludes 'httpclient'
        excludes 'httpcore'
        //excludes 'httpcomponents'
    }

    log 'warn'

    repositories {
        grailsCentral()

        mavenLocal()
        mavenCentral()
        mavenRepo "http://repository.codehaus.org"
        mavenRepo "http://repository.jboss.org/maven2" // For SAC
    }

    def htmlUnitVersion = '2.10'

    dependencies {
        test('org.codehaus.groovy.modules.http-builder:http-builder:0.5.2') {
            excludes 'groovy', 'xml-apis', 'xerces', 'httpcore'
        }

        // HtmlUnit stuff
        test("net.sourceforge.htmlunit:htmlunit:$htmlUnitVersion") {
            excludes 'xml-apis', 'xerces', 'httpcore'
        }
        test("net.sourceforge.htmlunit:htmlunit-core-js:$htmlUnitVersion") {
            excludes 'xml-apis', 'xerces', 'httpcore'
        }
//        compile( 'org.apache.httpcomponents:httpclient:4.2.3') {
//            excludes 'xml-apis', 'xerces'
//        }

        test('commons-codec:commons-codec:1.7') {
            excludes 'xml-apis', 'xerces', 'httpcore'
        }
        build('net.sourceforge.nekohtml:nekohtml:1.9.18') {
            excludes 'xml-apis', 'xerces', 'httpcore'
        }
        test('net.sourceforge.cssparser:cssparser:0.9.9') {
            excludes 'xml-apis', 'xerces', 'httpcore'
        }
        test('xalan:serializer:2.7.1') {
            excludes 'xml-apis', 'xerces', 'httpcore'
        }
        test('xalan:xalan:2.7.1') {
            excludes 'xml-apis', 'xerces', 'httpcore'
        }
        // test( 'xerces:xercesImpl:2.10.0') {
        //     excludes 'xml-apis'
        // }
        test('org.w3c.css:sac:1.3') {
            excludes 'xml-apis', 'xerces', 'httpcore'
        }
    }

    plugins {
        build(':release:3.0.1', ':rest-client-builder:1.0.3', ':tomcat:7.0.52.1') {
            export = false
        }
//        runtime ":hibernate:3.6.10.13", {
//            export = false
//        }
    }
}
