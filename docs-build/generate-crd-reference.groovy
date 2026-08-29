import groovy.yaml.YamlSlurper

import java.nio.file.Files
import java.nio.file.Path

Path repository = project.basedir.toPath().parent
Path crdFile = repository.resolve('charts/s3-resource-operator/crds/s3.dajudge.com.yaml')
Path referenceFile = repository.resolve('docs/reference/crds.md')

if (!Files.isRegularFile(crdFile)) {
    throw new IllegalStateException("CRD file not found: ${crdFile}")
}

String escapeCell(Object value) {
    if (value == null) {
        return '—'
    }
    String rendered = value.toString().replace('|', '\\|').replaceAll(/\s+/, ' ').trim()
    return rendered ?: '—'
}

String renderType(Map property) {
    String type = property.type ?: 'object'
    if (type == 'array') {
        type = "array<${property.items?.type ?: 'object'}>"
    }
    String rendered = "`${type}`"
    if (property.enum) {
        rendered += ' (' + property.enum.collect { "`${escapeCell(it)}`" }.join(' \\| ') + ')'
    }
    return rendered
}

List<List<String>> propertyRows(Map properties, Set required, String prefix = '') {
    List<List<String>> rows = []
    properties.each { String name, Object rawProperty ->
        Map property = rawProperty as Map
        String field = prefix ? "${prefix}.${name}" : name
        String defaultValue = property.containsKey('default') ? "`${escapeCell(property.default)}`" : '—'
        rows << [
                "`${field}`",
                renderType(property),
                required.contains(name) ? 'Yes' : 'No',
                defaultValue,
                escapeCell(property.description)
        ]
        if (property.properties instanceof Map) {
            Set nestedRequired = (property.required ?: []) as Set
            rows.addAll(propertyRows(property.properties as Map, nestedRequired, field))
        }
    }
    return rows
}

List<Map> crds = Files.readString(crdFile)
        .split(/(?m)^---\s*$/)
        .collect { it.trim() }
        .findAll { !it.isEmpty() }
        .collect { new YamlSlurper().parseText(it) as Map }

if (crds.isEmpty()) {
    throw new IllegalStateException("No CRDs found in ${crdFile}")
}

List<String> output = [
        '# CRD API reference',
        '',
        '> This file is generated from `charts/s3-resource-operator/crds/s3.dajudge.com.yaml`. Do not edit it by hand.',
        '>',
        '> Requiredness, defaults, enums, and descriptions below reflect the **shipped CRD OpenAPI schema**, not application-side fallback behavior.',
        ''
]

crds.each { Map crd ->
    String kind = crd.spec.names.kind
    String group = crd.spec.group
    String scope = crd.spec.scope
    crd.spec.versions.each { Map version ->
        Map schema = version.schema.openAPIV3Schema as Map
        Map specSchema = (schema.properties?.spec ?: [:]) as Map
        Map specProperties = (specSchema.properties ?: [:]) as Map
        Set required = (specSchema.required ?: []) as Set

        output.addAll([
                "## ${kind}",
                '',
                "API version: `${group}/${version.name}`  ",
                "Scope: ${scope}",
                '',
                '### `spec`',
                '',
                '| Field | Type | Required by CRD | Default | Description |',
                '| --- | --- | :---: | --- | --- |'
        ])

        propertyRows(specProperties, required).each { List<String> row ->
            output << "| ${row.join(' | ')} |"
        }

        Map statusSchema = (schema.properties?.status ?: [:]) as Map
        output.addAll(['', '### `status`', ''])
        if (statusSchema['x-kubernetes-preserve-unknown-fields'] == true) {
            output << 'The CRD preserves unknown status fields (`x-kubernetes-preserve-unknown-fields`).'
        } else if (statusSchema.properties instanceof Map) {
            output.addAll([
                    '| Field | Type | Required by CRD | Default | Description |',
                    '| --- | --- | :---: | --- | --- |'
            ])
            propertyRows(statusSchema.properties as Map, (statusSchema.required ?: []) as Set).each { List<String> row ->
                output << "| ${row.join(' | ')} |"
            }
        } else {
            output << 'No status schema is declared.'
        }
        output << ''
    }
}

String generated = output.join(System.lineSeparator()).stripTrailing() + System.lineSeparator()
boolean update = System.getProperty('docs.update', 'false').toBoolean()

if (update) {
    Files.createDirectories(referenceFile.parent)
    Files.writeString(referenceFile, generated)
    log.info("Updated ${repository.relativize(referenceFile)}")
} else {
    if (!Files.isRegularFile(referenceFile)) {
        throw new IllegalStateException("Generated reference is missing: ${referenceFile}. Run with -Ddocs.update=true")
    }
    String committed = Files.readString(referenceFile)
    if (committed != generated) {
        throw new IllegalStateException('Generated CRD reference is stale. Run ./mvnw -f docs-build/pom.xml verify -Ddocs.update=true and commit the result.')
    }
    log.info('Generated CRD reference is up to date')
}
