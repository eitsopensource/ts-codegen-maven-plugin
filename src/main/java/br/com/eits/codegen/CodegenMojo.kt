package br.com.eits.codegen

import java.util.stream.Collectors.toList

import java.beans.Introspector
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.net.MalformedURLException
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.Charset
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.AbstractMap
import java.util.ArrayList
import java.util.Arrays
import java.util.Calendar
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.Optional

import org.apache.commons.io.IOUtils
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.reflections.Reflections
import org.reflections.scanners.SubTypesScanner
import org.reflections.scanners.TypeAnnotationsScanner
import org.reflections.util.ConfigurationBuilder

/**
 *
 */
@Mojo(name = "generate-ts", requiresDependencyResolution = ResolutionScope.COMPILE, defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
class CodegenMojo : AbstractMojo() {
    /**
     *
     */
    @org.apache.maven.plugins.annotations.Parameter(defaultValue = "\${project}", readonly = true)
    private val project: MavenProject? = null

    /**
     *
     */
    @org.apache.maven.plugins.annotations.Parameter(defaultValue = "src/main/ts/src/generated", property = "moduleOutputDirectory", required = false)
    private val moduleOutputDirectory: File? = null

    @org.apache.maven.plugins.annotations.Parameter(property = "skip.generator", defaultValue = "\${skip.generator}")
    private val skip: Boolean = false

    @Throws(MojoExecutionException::class, MojoFailureException::class)
    override fun execute() {
        if (skip) {
            log.info("Skipping.")
            return
        }
        try {
            if (!moduleOutputDirectory!!.exists()) {
                if (!moduleOutputDirectory.mkdirs()) {
                    throw MojoExecutionException("Não foi possível criar a pasta de código gerado: " + moduleOutputDirectory.path)
                }
            }
            val urls = ArrayList<URL>(setOf<URL>(File(project!!.basedir, "target/classes").toURI().toURL()))
            urls.addAll(project.compileClasspathElements.map { File(it).toURI().toURL() })
            val classLoader = URLClassLoader(urls.toTypedArray())
            log.info("Instanciando scanner...")
            val reflections = Reflections(ConfigurationBuilder()
                    .addUrls(urls)
                    .addClassLoader(classLoader)
                    .setScanners(TypeAnnotationsScanner(), SubTypesScanner()))
            log.info("Escaneando classpath para buscar entidades...")

            val dataTransferObjectAnnotation = classLoader.loadClass("org.directwebremoting.annotations.DataTransferObject") as Class<out Annotation>
            val entityData = reflections.getTypesAnnotatedWith(dataTransferObjectAnnotation)
                    .stream().map({ entityClass -> extractDTOData(entityClass, dataTransferObjectAnnotation, classLoader) })
                    .collect(toList<Map<String, String>>())

            val generatedEntities = File(moduleOutputDirectory, "entities.ts")
            assert(!generatedEntities.exists() || generatedEntities.delete())
            assert(generatedEntities.createNewFile())
            Files.write(generatedEntities.toPath(),
                    String(
                            IOUtils.toByteArray(javaClass.getResourceAsStream("/templates/models.template.ts")),
                            Charset.defaultCharset()
                    ).replace("@MODELS@", renderEntities(entityData)).toByteArray())


            val remoteProxyAnnotation = classLoader.loadClass("org.directwebremoting.annotations.RemoteProxy") as Class<out Annotation>
            val imports = HashSet(Arrays.asList("Sort", "SortOrder", "SortDirection", "NullHandling", "PageRequest", "Page", "Pageable"))
            imports.addAll(entityData.map { map ->
                if (map.containsKey("ENUM")) map.get("ENUM") else map.get("ENTITY")
            }.map { type ->
                type!!.split(" ".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[0]
            })
            val serviceData = reflections.getTypesAnnotatedWith(remoteProxyAnnotation).map { this.renderServiceData(it) }

            val generatedServices = File(moduleOutputDirectory, "services.ts")
            assert(!generatedServices.exists() || generatedServices.delete())
            assert(generatedServices.createNewFile())
            Files.write(generatedServices.toPath(),
                    String(
                            IOUtils.toByteArray(javaClass.getResourceAsStream("/templates/services.template.ts")),
                            Charset.defaultCharset()
                    )
                            .replace("@IMPORTS@", imports.toTypedArray<String>().joinToString(", "))
                            .replace("@SERVICES@", renderServices(serviceData))
                            .toByteArray())

            val servicesWrapper = File(moduleOutputDirectory, "services-wrapper.ts")
            assert(!servicesWrapper.exists() || servicesWrapper.delete())
            assert(servicesWrapper.createNewFile())
            Files.write(servicesWrapper.toPath(), IOUtils.toByteArray(javaClass.getResourceAsStream("/templates/services-wrapper.ts")))

            val generatedModule = File(moduleOutputDirectory, "generated.module.ts")
            assert(!generatedModule.exists() || generatedModule.delete())
            assert(generatedModule.createNewFile())
            Files.write(generatedModule.toPath(),
                    String(
                            IOUtils.toByteArray(javaClass.getResourceAsStream("/templates/generated.module.template.ts")),
                            Charset.defaultCharset()
                    ).replace("@SERVICES@", serviceData.map { map -> map.get("CLASS") }.joinToString(", "))
                            .toByteArray()
            )
        } catch (e: ClassNotFoundException) {
            log.info("Não estamos em um projeto com DWR, pulando")
        } catch (e: Exception) {
            throw MojoExecutionException("", e)
        }

    }

    @Throws(Exception::class)
    private fun renderServices(serviceData: List<Map<String, String>>): String {
        val sb = StringBuilder()
        val template = String(IOUtils.toByteArray(javaClass.getResourceAsStream("/templates/service.template.ts")), Charset.defaultCharset())
        for (data in serviceData) {
            var currentTemplate = template
            for ((key, value) in data) {
                currentTemplate = currentTemplate.replace("@$key@", value)
            }
            sb.append(currentTemplate).append("\n\n")
        }
        return sb.toString()
    }

    private fun renderServiceData(serviceClass: Class<*>): Map<String, String> {
        val map = HashMap<String, String>()

        val typescriptMethods = serviceClass.methods
                .filter { method -> method.declaringClass == serviceClass }
                .map { method ->
                    val returnType = extractGenericReturnType(method.genericReturnType, true)
                    val parameters = method.parameters.map { parameter -> parameter.name + "?: " + extractGenericReturnType(parameter.parameterizedType, false) }
                    val parameterNames = if (parameters.isNotEmpty()) ", " + method.parameters.map { it.name }.joinToString(", ") else ""

                    String.format("    public %s(%s): Observable<%s> {\n        return dwrWrapper(this.brokerConfiguration, '%s', '%s'%s) as Observable<%s>;\n    }\n",
                            method.name, parameters.joinToString(", "), returnType, Introspector.decapitalize(serviceClass.simpleName), method.name, parameterNames, returnType)
                }.toMutableList()

        map["CLASS"] = serviceClass.simpleName
        map["METHODS"] = typescriptMethods.stream().reduce("") { m, x -> m + x + "\n" }
        map["RETURN_TYPES"] = extractRealTimeMethodsFromService(serviceClass)
        map["INSTANCE"] = Introspector.decapitalize(serviceClass.simpleName)

        return map
    }

    /**
     * Os métodos passíveis de serem atualizados em tempo real são aqueles anotados com @Transactional(readOnly = true).
     * Esta anotação garante que possamos chamar uma lista várias vezes sem penalidade.
     *
     * @param serviceClass classe
     * @return JSON no formato {método: classe da entidade de retorno}
     */
    private fun extractRealTimeMethodsFromService(serviceClass: Class<*>): String {
        return "{" +
                Arrays.stream(serviceClass.methods)
                        .filter { method -> method.declaringClass == serviceClass }
                        .filter { method -> findTransactionalReadOnly(method.annotations).isPresent }
                        .filter { method -> !Map::class.java.isAssignableFrom(method.returnType) } // Não podemos observar estes, pelo menos por enquanto
                        .map { method ->
                            val fqReturnType = if (Collection::class.java.isAssignableFrom(method.returnType) || method.returnType.canonicalName == "org.springframework.data.domain.Page")
                                ((method.genericReturnType as ParameterizedType).actualTypeArguments[0] as Class<*>).canonicalName
                            else
                                method.returnType.canonicalName
                            method.name + ": \'" + fqReturnType + "\'"
                        }
                        .reduce("") { m, x -> "$m$x,\n    " } + "}"
    }

    private fun findTransactionalReadOnly(annotations: Array<Annotation>): Optional<Annotation> {
        return Arrays.stream(annotations)
                .filter { annotation -> annotation.javaClass.interfaces[0].canonicalName == "org.springframework.transaction.annotation.Transactional" }
                .filter { annotation -> wrapInvoke(wrapGetMethod(annotation.javaClass, "readOnly"), annotation) as Boolean }
                .findFirst()
    }

    private fun extractGenericReturnType(genericType: Type, isReturnValue: Boolean): String {
        if (genericType is ParameterizedType) {
            val rawType = genericType.rawType as Class<*>
            if (Map::class.java.isAssignableFrom(rawType)) {
                return translateJavaTypeToTypescript(rawType, genericType)
            }
            val genericParameter = Arrays.stream(genericType.actualTypeArguments)
                    .filter { arg -> arg is Class<*> }
                    .map { arg -> translateJavaTypeToTypescript(arg as Class<*>, arg) }
                    .findAny().orElse("any")
            return if (Collection::class.java.isAssignableFrom(rawType)) {
                "$genericParameter[]"
            } else rawType.simpleName + "<" + genericParameter + ">"
        }
        return if (isReturnValue && genericType is Class<*> && genericType.simpleName == "FileTransfer") {
            "string" // DWR transforma o FileTransfer em uma URL relativa
        } else translateJavaTypeToTypescript(genericType as Class<*>, genericType)
    }

    @Throws(Exception::class)
    private fun renderEntities(data: List<Map<String, String>>): String {
        val sb = StringBuilder()
        val entityTemplate = String(IOUtils.toByteArray(javaClass.getResourceAsStream("/templates/entity.template.ts")), Charset.defaultCharset())
        val enumTemplate = String(IOUtils.toByteArray(javaClass.getResourceAsStream("/templates/enum.template.ts")), Charset.defaultCharset())
        data.forEach { item ->
            var template = if (item.containsKey("ENUM")) enumTemplate else entityTemplate
            for ((key, value) in item) {
                template = template.replace("@$key@", value)
            }
            sb.append(template)
            sb.append("\n\n")
        }
        return sb.toString()
    }

    private fun extractDTOData(entityClass: Class<*>, dwrAnnotation: Class<out Annotation>, classLoader: ClassLoader): Map<String, String> {
        return if (Enum::class.java.isAssignableFrom(entityClass)) {
            extractEnumData(entityClass as Class<out Enum<*>>, dwrAnnotation, classLoader)
        } else {
            extractEntityData(entityClass, dwrAnnotation, classLoader)
        }

    }

    private fun extractEntityData(entityClass: Class<*>, dwrAnnotation: Class<out Annotation>, classLoader: ClassLoader): Map<String, String> {
        log.info("Encontrou entidade: " + entityClass.name)
        val dwrExcludedFields = getExcludedFieldNameList(entityClass, dwrAnnotation, classLoader)
        val fields = ArrayList<Field>()
        fields.addAll(entityClass.declaredFields
                .filter { filter -> !Modifier.isFinal(filter.modifiers) }
                .filter { field -> !dwrExcludedFields.contains(field.name) }
                .toList())

        var entitySuperclass: Class<*> = entityClass.superclass
        while (entitySuperclass.getAnnotation(dwrAnnotation) == null && entitySuperclass != Any::class.java) {
            fields.addAll(entitySuperclass.declaredFields
                    .filter { filter -> !Modifier.isFinal(filter.modifiers) }
                    .toList())
            entitySuperclass = entitySuperclass.superclass
        }
        val map = HashMap<String, String>()
        map["ENTITY"] = if (entityClass.superclass.getAnnotation(dwrAnnotation) != null) entityClass.simpleName + " extends " + entityClass.superclass.simpleName else entityClass.simpleName
        map["FIELDS"] = fields.map { field -> field.name + "?: " + getTypescriptFieldType(field) }.joinToString(",\n    ")
        return map
    }

    private fun extractEnumData(entityClass: Class<out Enum<*>>, dwrAnnotation: Class<out Annotation>, classLoader: ClassLoader): Map<String, String> {
        val values = entityClass.enumConstants.map { "'${it.name}'" }
        val map = HashMap<String, String>()
        map["ENUM"] = entityClass.simpleName
        map["VALUES"] = values.joinToString(" | ")
        map["ARRAY"] = "[" + values.joinToString(", ") + "]"
        return map
    }

    private fun getExcludedFieldNameList(entityClass: Class<*>, annotationClass: Class<out Annotation>, classLoader: ClassLoader): List<String> {
        try {
            val paramsMethod = annotationClass.getMethod("params")
            val paramClass = classLoader.loadClass("org.directwebremoting.annotations.Param")
            val nameMethod = paramClass.getMethod("name")
            val valueMethod = paramClass.getMethod("value")
            val dtoAnnotation = entityClass.getAnnotation(annotationClass)
            val paramsList = (paramsMethod.invoke(dtoAnnotation) as Array<*>)
                    .map { (nameMethod.invoke(it) as String) to (valueMethod.invoke(it) as String) }.toMap()
            return paramsList
                    .filter { entry -> entry.key == "exclude" }
                    .map { it.value }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

    }

    private fun getTypescriptFieldType(field: Field): String {
        if (field.genericType is ParameterizedType) {
            if (Collection::class.java.isAssignableFrom(field.type)) {
                val collectionType = (field.genericType as ParameterizedType).actualTypeArguments[0] as Class<*>
                return translateJavaTypeToTypescript(collectionType, field.genericType) + "[]"
            }
        }
        return translateJavaTypeToTypescript(field.type, field.genericType)
    }

    private fun translateJavaTypeToTypescript(type: Class<*>, genericType: Type): String {
        if (type.isArray) {
            return translateJavaTypeToTypescript(type.componentType, genericType)
        } else if (Number::class.java.isAssignableFrom(type) || Arrays.asList("byte", "char", "int", "long", "float", "double").contains(type.name)) {
            return "number"
        } else if (Arrays.asList<Class<*>>(Calendar::class.java, LocalDateTime::class.java, LocalDate::class.java).contains(type)) {
            return "Date"
        } else if (OffsetDateTime::class.java == type) {
            return "string"
        } else if (String::class.java.isAssignableFrom(type)) {
            return "string"
        } else if (Boolean::class.java.isAssignableFrom(type)) {
            return "boolean"
        } else if (type.canonicalName.startsWith("com.vividsolutions.jts.geom")) {
            return "string"
        } else if (type.simpleName == "FileTransfer") {
            return "HTMLInputElement" // FileTransfer equivale à input[type="file"]
        } else if (Map::class.java.isAssignableFrom(type)) {
            if (type == genericType) {
                return "{[key: string]: any}"
            } else if (genericType is ParameterizedType) {
                val types = genericType.actualTypeArguments
                val first = types[0]
                val second = types[1]
                val firstType = if(first is Class<*> && Number::class.java.isAssignableFrom(first)) {
                    "number"
                } else if(first is Class<*> && String::class.java.isAssignableFrom(first)) {
                    "string"
                } else if(first is Class<*> && Enum::class.java.isAssignableFrom(first)) {
                    "string"
//                    translateJavaTypeToTypescript(first, first)
                } else {
                    "any"
                }
                val secondType = translateJavaTypeToTypescript((if (second is ParameterizedType) second.rawType else second) as Class<*>, second)
                return "{[key: $firstType]: $secondType}"
            }
        } else if (Collection::class.java.isAssignableFrom(type)) {
            if(type == genericType) {
                return "any[]"
            } else if(genericType is ParameterizedType) {
                val argument = genericType.actualTypeArguments[0]
                val argumentType = translateJavaTypeToTypescript((if(argument is ParameterizedType) argument.rawType else argument) as Class<*>, argument)
                return "$argumentType[]"
            }
        }
        return type.simpleName
    }

    private fun wrapInvoke(method: Method, `object`: Any): Any {
        try {
            return method.invoke(`object`)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

    }

    private fun wrapGetMethod(clazz: Class<*>, name: String, vararg arguments: Class<*>): Method {
        try {
            return clazz.getMethod(name, *arguments)
        } catch (e: NoSuchMethodException) {
            throw RuntimeException(e)
        }

    }
}
