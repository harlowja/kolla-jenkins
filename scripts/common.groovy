// common.groovy

// Shared common routines/code that aren't yet in any library that
// will get included into the various other scripts at job creation
// time (and won't be loaded at run-time)

import com.cloudbees.groovy.cps.NonCPS

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Dumps a map to json (pretty or not).
 */
@NonCPS
def dump_json(Map data, Boolean pretty=false) {
    def maker = new JsonOutput()
    def blob = maker.toJson(data)
    if(pretty) {
        blob = maker.prettyPrint(blob)
    }
    return blob
}

@NonCPS
def pretty_format(Map data) {
    def keys = data.keySet()
    keys = keys.sort()
    def lines = []
    for(k in keys) {
        lines.add("${k} => ${data[k]}")
    }
    return lines.join("\n")
}

// Common libraries that aren't built-in that we want to ensure
// are everywhere... (we use yaml a lot so import it...)
@Grapes(
    @Grab(group='org.yaml', module='snakeyaml', version='1.13')
)
import org.yaml.snakeyaml.Yaml

@Grapes(
    @Grab(group='org.ini4j', module='ini4j', version='0.5.2')
)
import org.ini4j.Wini

@NonCPS
def load_yaml(String blob) {
    def reader = new Yaml()
    return reader.load(blob)
}

@NonCPS
def load_json(String blob) {
    def slurper = new JsonSlurper()
    return slurper.parseText(blob)
}

// common.groovy
