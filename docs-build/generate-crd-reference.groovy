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
    String type = property.get('type') ?: 'object'
    Map items = (property.get('items') ?: [:]) as Map
    if (type == 'array') {
        type = "array<${items.get('type') ?: 'object'}>"
    }
    String rendered = "`${type}`"
    if (property.get('enum')) {
        rendered += ' (' + (property.get('enum') as List).collect { "`${escapeCell(it)}`" }.join(' \\| ') + ')'
    }
    return rendered
}

List<List<String>> propertyRows(Map properties, Set required, String prefix = '') {
    List<List<String>> rows = []
    properties.each { String name, Object rawProperty ->
        Map property = rawProperty as Map
        String field = prefix ? "${prefix}.${name}" : name
        String defaultValue = property.containsKey('default') ? "`${escapeCell(property.get('default'))}`" : '—'
        rows << [
                "`${field}`",
                renderType(property),
                required.contains(name) ? 'Yes' : 'No',
                defaultValue,
                escapeCell(property.get('description'))
        ]
        if (property.get('properties') instanceof Map) {
            Set nestedRequired = (property.get('required') ?: []) as Set
            rows.addAll(propertyRows(property.get('properties') as Map, nestedRequired, field))
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
    Map crdSpec = crd.get('spec') as Map
    Map names = crdSpec.get('names') as Map
    String kind = names.get('kind')
    String group = crdSpec.get('group')
    String scope = crdSpec.get('scope')
    (crdSpec.get('versions') as List<Map>).each { Map version ->
        Map versionSchema = version.get('schema') as Map
        Map schema = versionSchema.get('openAPIV3Schema') as Map
        Map schemaProperties = (schema.get('properties') ?: [:]) as Map
        Map specSchema = (schemaProperties.get('spec') ?: [:]) as Map
        Map specProperties = (specSchema.get('properties') ?: [:]) as Map
        if (specSchema.containsKey('properties') && specProperties.isEmpty()) {
            throw new IllegalStateException("Spec schema for ${kind} declares properties but none were extracted")
        }
        Set required = (specSchema.get('required') ?: []) as Set

        output.addAll([
                "## ${kind}",
                '',
                "API version: `${group}/${version.get('name')}`  ",
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

        Map statusSchema = (schemaProperties.get('status') ?: [:]) as Map
        output.addAll(['', '### `status`', ''])
        if (statusSchema.get('x-kubernetes-preserve-unknown-fields') == true) {
            output << 'The CRD preserves unknown status fields (`x-kubernetes-preserve-unknown-fields`).'
        } else if (statusSchema.get('properties') instanceof Map) {
            output.addAll([
                    '| Field | Type | Required by CRD | Default | Description |',
                    '| --- | --- | :---: | --- | --- |'
            ])
            propertyRows(statusSchema.get('properties') as Map, (statusSchema.get('required') ?: []) as Set).each { List<String> row ->
                output << "| ${row.join(' | ')} |"
            }
        } else {
            output << 'No status schema is declared.'
        }
        output << ''
    }
}

String generated = output.join('\n').stripTrailing() + '\n'
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
