package br.com.eits.codegen;

import static java.util.stream.Collectors.toList;

import java.beans.Introspector;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ConfigurationBuilder;

/**
 *
 */
@Mojo(name = "generate-ts", requiresDependencyResolution = ResolutionScope.COMPILE, defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class CodegenMojo extends AbstractMojo
{
	/**
	 *
	 */
	@org.apache.maven.plugins.annotations.Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject project;

	/**
	 *
	 */
	@org.apache.maven.plugins.annotations.Parameter(defaultValue = "src/main/ts/src/generated", property = "moduleOutputDirectory", required = false)
	private File moduleOutputDirectory;

	@org.apache.maven.plugins.annotations.Parameter(property = "skip.generator", defaultValue = "${skip.generator}")
	private boolean skip;

	public void execute() throws MojoExecutionException, MojoFailureException
	{
		if ( skip )
		{
			getLog().info( "Skipping." );
			return;
		}
		try
		{
			if ( !moduleOutputDirectory.exists() )
			{
				if ( !moduleOutputDirectory.mkdirs() )
				{
					throw new MojoExecutionException( "Não foi possível criar a pasta de código gerado: " + moduleOutputDirectory.getPath() );
				}
			}
			ArrayList<URL> urls = new ArrayList<>( Collections.singleton( new File( project.getBasedir(), "target/classes" ).toURI().toURL() ) );
			urls.addAll( project.getCompileClasspathElements().stream().map( spec -> {
				try
				{
					return new File( spec ).toURI().toURL();
				}
				catch ( MalformedURLException e )
				{
					e.printStackTrace();
					return null;
				}
			} ).collect( toList() ) );
			URLClassLoader classLoader = new URLClassLoader( urls.toArray( new URL[]{} ) );
			getLog().info( "Instanciando scanner..." );
			Reflections reflections = new Reflections( new ConfigurationBuilder()
					.addUrls( urls )
					.addClassLoader( classLoader )
					.setScanners( new TypeAnnotationsScanner(), new SubTypesScanner() ) );
			getLog().info( "Escaneando classpath para buscar entidades..." );

			@SuppressWarnings("unchecked")
			Class<? extends Annotation> dataTransferObjectAnnotation = (Class<? extends Annotation>) classLoader.loadClass( "org.directwebremoting.annotations.DataTransferObject" );
			List<Map<String, String>> entityData =
					reflections.getTypesAnnotatedWith( dataTransferObjectAnnotation )
							.stream().map( entityClass -> extractDTOData( entityClass, dataTransferObjectAnnotation, classLoader ) )
							.collect( toList() );

			File generatedEntities = new File( moduleOutputDirectory, "entities.ts" );
			assert !generatedEntities.exists() || generatedEntities.delete();
			assert generatedEntities.createNewFile();
			Files.write( generatedEntities.toPath(),
					new String(
							IOUtils.toByteArray( getClass().getResourceAsStream( "/templates/models.template.ts" ) ),
							Charset.defaultCharset()
					).replace( "@MODELS@", renderEntities( entityData ) ).getBytes() );


			@SuppressWarnings("unchecked")
			Class<? extends Annotation> remoteProxyAnnotation = (Class<? extends Annotation>) classLoader.loadClass( "org.directwebremoting.annotations.RemoteProxy" );
			Set<String> imports = new HashSet<>( Arrays.asList( "Sort", "SortOrder", "SortDirection", "NullHandling", "PageRequest", "Page", "Pageable" ) );
			imports.addAll( entityData.stream().map( map -> map.containsKey( "ENUM" ) ? map.get( "ENUM" ) : map.get( "ENTITY" ) ).map( type -> type.split( " " )[0] ).collect( toList() ) );
			List<Map<String, String>> serviceData = reflections.getTypesAnnotatedWith( remoteProxyAnnotation )
					.stream().map( this::renderServiceData ).collect( toList() );

			File generatedServices = new File( moduleOutputDirectory, "services.ts" );
			assert !generatedServices.exists() || generatedServices.delete();
			assert generatedServices.createNewFile();
			Files.write( generatedServices.toPath(),
					new String(
							IOUtils.toByteArray( getClass().getResourceAsStream( "/templates/services.template.ts" ) ),
							Charset.defaultCharset()
					)
							.replace( "@IMPORTS@", String.join( ", ", imports.toArray( new String[]{} ) ) )
							.replace( "@SERVICES@", renderServices( serviceData ) )
							.getBytes() );

			File servicesWrapper = new File( moduleOutputDirectory, "services-wrapper.ts" );
			assert !servicesWrapper.exists() || servicesWrapper.delete();
			assert servicesWrapper.createNewFile();
			Files.write( servicesWrapper.toPath(), IOUtils.toByteArray( getClass().getResourceAsStream( "/templates/services-wrapper.ts" ) ) );

			File generatedModule = new File( moduleOutputDirectory, "generated.module.ts" );
			assert !generatedModule.exists() || generatedModule.delete();
			assert generatedModule.createNewFile();
			Files.write( generatedModule.toPath(),
					new String(
							IOUtils.toByteArray( getClass().getResourceAsStream( "/templates/generated.module.template.ts" ) ),
							Charset.defaultCharset()
					).replace( "@SERVICES@", String.join( ", ", serviceData.stream().map( map -> map.get( "CLASS" ) ).toArray( String[]::new ) ) )
							.getBytes()
			);
		}
		catch ( ClassNotFoundException e )
		{
			getLog().info( "Não estamos em um projeto com DWR, pulando" );
		}
		catch ( Exception e )
		{
			throw new MojoExecutionException( "", e );
		}
	}

	private String renderServices( List<Map<String, String>> serviceData ) throws Exception
	{
		StringBuilder sb = new StringBuilder();
		final String template = new String( IOUtils.toByteArray( getClass().getResourceAsStream( "/templates/service.template.ts" ) ), Charset.defaultCharset() );
		for ( Map<String, String> data : serviceData )
		{
			String currentTemplate = template;
			for ( Map.Entry<String, String> entry : data.entrySet() )
			{
				currentTemplate = currentTemplate.replace( "@" + entry.getKey() + "@", entry.getValue() );
			}
			sb.append( currentTemplate ).append( "\n\n" );
		}
		return sb.toString();
	}

	private Map<String, String> renderServiceData( Class<?> serviceClass )
	{
		Map<String, String> map = new HashMap<>();

		List<String> typescriptMethods = Arrays.stream( serviceClass.getMethods() )
				.filter( method -> method.getDeclaringClass().equals( serviceClass ) )
				.map( method ->
				{
					String returnType = extractGenericReturnType( method.getGenericReturnType(), true );
					String[] parameters = Arrays.stream( method.getParameters() ).map( parameter -> parameter.getName() + "?: " + extractGenericReturnType( parameter.getParameterizedType(), false ) ).toArray( String[]::new );
					String parameterNames = parameters.length > 0 ? ", " + String.join( ", ", Arrays.stream( method.getParameters() ).map( Parameter::getName ).toArray( String[]::new ) ) : "";

					return String.format( "    public %s(%s): Observable<%s> {\n        return dwrWrapper(this.brokerConfiguration, '%s', '%s'%s) as Observable<%s>;\n    }\n",
							method.getName(), String.join( ", ", parameters ), returnType, Introspector.decapitalize( serviceClass.getSimpleName() ), method.getName(), parameterNames, returnType );
				}).collect(toList());

		map.put( "CLASS", serviceClass.getSimpleName() );
		map.put( "METHODS", typescriptMethods.stream().reduce( "", ( m, x ) -> m + x + "\n" ) );
		map.put( "RETURN_TYPES", extractRealTimeMethodsFromService( serviceClass ) );
		map.put( "INSTANCE", Introspector.decapitalize( serviceClass.getSimpleName() ) );

		return map;
	}

	/**
	 * Os métodos passíveis de serem atualizados em tempo real são aqueles anotados com @Transactional(readOnly = true).
	 * Esta anotação garante que possamos chamar uma lista várias vezes sem penalidade.
	 *
	 * @param serviceClass classe
	 * @return JSON no formato {método: classe da entidade de retorno}
	 */
	private String extractRealTimeMethodsFromService( Class<?> serviceClass )
	{
		return "{" +
				Arrays.stream( serviceClass.getMethods() )
						.filter( method -> method.getDeclaringClass().equals( serviceClass ) )
						.filter( method -> findTransactionalReadOnly( method.getAnnotations() ).isPresent() )
						.filter( method -> !Map.class.isAssignableFrom( method.getReturnType() ) ) // Não podemos observar estes, pelo menos por enquanto
						.map( method ->
						{
							String fqReturnType = Collection.class.isAssignableFrom( method.getReturnType() ) || method.getReturnType().getCanonicalName().equals( "org.springframework.data.domain.Page" ) ?
									((Class<?>) ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0]).getCanonicalName() :
									method.getReturnType().getCanonicalName();
							return method.getName() + ": \'" + fqReturnType + "\'";
						} )
				.reduce( "", (m, x) -> m + x + ",\n    " ) + "}";
	}

	private Optional<Annotation> findTransactionalReadOnly( Annotation[] annotations )
	{
		return Arrays.stream( annotations )
				.filter( annotation -> annotation.getClass().getInterfaces()[0].getCanonicalName().equals( "org.springframework.transaction.annotation.Transactional" ) )
				.filter( annotation -> (Boolean) wrapInvoke( wrapGetMethod( annotation.getClass(), "readOnly" ), annotation )  )
				.findFirst();
	}

	private String extractGenericReturnType( Type genericType, boolean isReturnValue )
	{
		if ( genericType instanceof ParameterizedType )
		{
			Class<?> rawType = (Class<?>) ((ParameterizedType) genericType).getRawType();
			if ( Map.class.isAssignableFrom( rawType ) )
			{
				return "any";
			}
			String genericParameter = Arrays.stream( ((ParameterizedType) genericType).getActualTypeArguments() )
					.filter( arg -> arg instanceof Class<?> )
					.map( arg -> translateJavaTypeToTypescript( (Class<?>) arg ) )
					.findAny().orElse( "any" );
			if ( Collection.class.isAssignableFrom( rawType ) )
			{
				return genericParameter + "[]";
			}
			return rawType.getSimpleName() + "<" + genericParameter + ">";
		}
		if ( isReturnValue && genericType instanceof Class && ((Class<?>) genericType).getSimpleName().equals( "FileTransfer" ) )
		{
			return "string"; // DWR transforma o FileTransfer em uma URL relativa
		}
		return translateJavaTypeToTypescript( (Class<?>) genericType );
	}

	private String renderEntities( List<Map<String, String>> data ) throws Exception
	{
		StringBuilder sb = new StringBuilder();
		final String entityTemplate = new String( IOUtils.toByteArray( getClass().getResourceAsStream( "/templates/entity.template.ts" ) ), Charset.defaultCharset() );
		final String enumTemplate = new String( IOUtils.toByteArray( getClass().getResourceAsStream( "/templates/enum.template.ts" ) ), Charset.defaultCharset() );
		data.forEach( item ->
		{
			String template = item.containsKey( "ENUM" ) ? enumTemplate : entityTemplate;
			for ( Map.Entry<String, String> entry : item.entrySet() )
			{
				template = template.replace( "@" + entry.getKey() + "@", entry.getValue() );
			}
			sb.append( template );
			sb.append( "\n\n" );
		} );
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> extractDTOData( Class<?> entityClass, Class<? extends Annotation> dwrAnnotation, ClassLoader classLoader )
	{
		if ( Enum.class.isAssignableFrom( entityClass ) )
		{
			return extractEnumData( (Class<? extends Enum>) entityClass, dwrAnnotation, classLoader );
		}
		else
		{
			return extractEntityData( entityClass, dwrAnnotation, classLoader );
		}

	}

	private Map<String, String> extractEntityData( Class<?> entityClass, Class<? extends Annotation> dwrAnnotation, ClassLoader classLoader )
	{
		getLog().info( "Encontrou entidade: " + entityClass.getName() );
		List<String> dwrExcludedFields = getExcludedFieldNameList( entityClass, dwrAnnotation, classLoader );
		ArrayList<Field> fields = new ArrayList<>();
		fields.addAll( Arrays.stream( entityClass.getDeclaredFields() )
				.filter( filter -> !Modifier.isFinal( filter.getModifiers() ) )
				.filter( field -> !dwrExcludedFields.contains( field.getName() ) )
				.collect( toList() ) );

		for (
				Class<?> entitySuperclass = entityClass.getSuperclass();
				entitySuperclass.getAnnotation( dwrAnnotation ) == null &&
						!entitySuperclass.equals( Object.class );
				entitySuperclass = entitySuperclass.getSuperclass() )
		{
			fields.addAll( Arrays.stream( entitySuperclass.getDeclaredFields() )
					.filter( filter -> !Modifier.isFinal( filter.getModifiers() ) )
					.collect( toList() ) );
		}
		HashMap<String, String> map = new HashMap<>();
		map.put( "ENTITY", entityClass.getSuperclass().getAnnotation( dwrAnnotation ) != null ? entityClass.getSimpleName() + " extends " + entityClass.getSuperclass().getSimpleName() : entityClass.getSimpleName() );
		map.put( "FIELDS", String.join( ",\n    ",
				fields.stream().map( field ->
						field.getName() + "?: " + getTypescriptFieldType( field ) )
						.toArray( String[]::new ) ) );
		return map;
	}

	private Map<String, String> extractEnumData( Class<? extends Enum> entityClass, Class<? extends Annotation> dwrAnnotation, ClassLoader classLoader )
	{
		String[] values = Arrays.stream( entityClass.getEnumConstants() )
				.map( val -> "'" + val.name() + "'" )
				.toArray( String[]::new );
		HashMap<String, String> map = new HashMap<>();
		map.put( "ENUM", entityClass.getSimpleName() );
		map.put( "VALUES", String.join( " | ", values ) );
		map.put( "ARRAY", "[" + String.join( ", ", values ) + "]" );
		return map;
	}

	private List<String> getExcludedFieldNameList( Class<?> entityClass, Class<? extends Annotation> annotationClass, ClassLoader classLoader )
	{
		try
		{
			Method paramsMethod = annotationClass.getMethod( "params" );
			Class<?> paramClass = classLoader.loadClass( "org.directwebremoting.annotations.Param" );
			Method nameMethod = paramClass.getMethod( "name" );
			Method valueMethod = paramClass.getMethod( "value" );
			Annotation dtoAnnotation = entityClass.getAnnotation( annotationClass );

			List<Map.Entry<String, String>> paramsList = Arrays.stream( (Object[]) paramsMethod.invoke( dtoAnnotation ) )
					.map( param -> new AbstractMap.SimpleEntry<>( (String) wrapInvoke( nameMethod, param ), (String) wrapInvoke( valueMethod, param ) ) )
					.collect( toList() );
			return
					paramsList.stream()
							.filter( entry -> entry.getKey().equals( "exclude" ) )
							.map( Map.Entry::getValue )
							.collect( toList() );
		}
		catch ( Exception e )
		{
			throw new RuntimeException( e );
		}
	}

	private String getTypescriptFieldType( Field field )
	{
		if ( field.getGenericType() instanceof ParameterizedType )
		{
			if ( Collection.class.isAssignableFrom( field.getType() ) )
			{
				Class<?> collectionType = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
				return translateJavaTypeToTypescript( collectionType ) + "[]";
			}
		}
		return translateJavaTypeToTypescript( field.getType() );
	}

	private String translateJavaTypeToTypescript( Class<?> type )
	{
		if ( type.isArray() )
		{
			return translateJavaTypeToTypescript( type.getComponentType() );
		}
		else if ( Number.class.isAssignableFrom( type ) || Arrays.asList( "byte", "char", "int", "long", "float", "double" ).contains( type.getName() ) )
		{
			return "number";
		}
		else if ( Arrays.asList( Calendar.class, LocalDateTime.class, LocalDate.class ).contains( type ) )
		{
			return "Date";
		}
		else if ( String.class.isAssignableFrom( type ) )
		{
			return "string";
		}
		else if ( Boolean.class.isAssignableFrom( type ) )
		{
			return "boolean";
		}
		else if ( type.getCanonicalName().startsWith( "com.vividsolutions.jts.geom" ) )
		{
			return "string";
		}
		else if ( type.getSimpleName().equals( "FileTransfer" ) )
		{
			return "HTMLInputElement"; // FileTransfer equivale à input[type="file"]
		}
		else if ( Collection.class.isAssignableFrom( type ) )
		{
			return "any[]";
		}
		return type.getSimpleName();
	}

	private Object wrapInvoke( Method method, Object object )
	{
		try
		{
			return method.invoke( object );
		}
		catch ( Exception e )
		{
			throw new RuntimeException( e );
		}
	}

	private Method wrapGetMethod( Class<?> clazz, String name, Class<?>... arguments )
	{
		try
		{
			return clazz.getMethod( name, arguments );
		}
		catch ( NoSuchMethodException e )
		{
			throw new RuntimeException( e );
		}
	}
}
