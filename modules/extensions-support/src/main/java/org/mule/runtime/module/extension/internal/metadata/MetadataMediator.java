/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.metadata;

import static java.util.stream.Collectors.toList;
import static org.mule.runtime.api.metadata.descriptor.builder.MetadataDescriptorBuilder.typeDescriptor;
import static org.mule.runtime.api.metadata.resolving.FailureCode.NO_DYNAMIC_TYPE_AVAILABLE;
import static org.mule.runtime.api.metadata.resolving.MetadataResult.failure;
import static org.mule.runtime.api.metadata.resolving.MetadataResult.mergeResults;
import static org.mule.runtime.api.metadata.resolving.MetadataResult.success;
import static org.mule.runtime.module.extension.internal.util.IntrospectionUtils.getContentParameter;
import static org.mule.runtime.module.extension.internal.util.IntrospectionUtils.getMetadataKeyParts;
import static org.mule.runtime.module.extension.internal.util.IntrospectionUtils.isNullType;
import static org.mule.runtime.module.extension.internal.util.IntrospectionUtils.isVoid;
import org.mule.metadata.api.builder.BaseTypeBuilder;
import org.mule.metadata.api.builder.UnionTypeBuilder;
import org.mule.metadata.api.model.MetadataFormat;
import org.mule.metadata.api.model.MetadataType;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.metadata.MetadataAware;
import org.mule.runtime.api.metadata.MetadataContext;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.metadata.MetadataResolvingException;
import org.mule.runtime.api.metadata.descriptor.ComponentMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.OutputMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.TypeMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.builder.ComponentMetadataDescriptorBuilder;
import org.mule.runtime.api.metadata.descriptor.builder.MetadataDescriptorBuilder;
import org.mule.runtime.api.metadata.resolving.MetadataContentResolver;
import org.mule.runtime.api.metadata.resolving.MetadataKeysResolver;
import org.mule.runtime.api.metadata.resolving.MetadataOutputResolver;
import org.mule.runtime.api.metadata.resolving.MetadataResult;
import org.mule.runtime.core.util.collection.ImmutableListCollector;
import org.mule.runtime.extension.api.annotation.metadata.Content;
import org.mule.runtime.extension.api.annotation.metadata.MetadataKeyId;
import org.mule.runtime.extension.api.annotation.metadata.MetadataKeyPart;
import org.mule.runtime.extension.api.introspection.RuntimeComponentModel;
import org.mule.runtime.extension.api.introspection.RuntimeExtensionModel;
import org.mule.runtime.extension.api.introspection.metadata.MetadataResolverFactory;
import org.mule.runtime.extension.api.introspection.metadata.NullMetadataKey;
import org.mule.runtime.extension.api.introspection.parameter.ParameterModel;
import org.mule.runtime.extension.api.introspection.property.SubTypesModelProperty;
import org.mule.runtime.module.extension.internal.introspection.SubTypesMappingContainer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Resolves a Component's Metadata by coordinating the several moving parts that are
 * affected by the Metadata fetching process, so that such pieces can remain decoupled.
 * <p/>
 * This mediator will coordinate the resolvers: {@link MetadataResolverFactory},
 * {@link MetadataKeysResolver}, {@link MetadataContentResolver} and {@link MetadataOutputResolver},
 * and the descriptors that represent their results: {@link ComponentMetadataDescriptor},
 * {@link OutputMetadataDescriptor} and {@link TypeMetadataDescriptor}
 *
 * @since 4.0
 */
public class MetadataMediator
{

    private final RuntimeComponentModel componentModel;
    private final MetadataResolverFactory resolverFactory;
    private final Optional<ParameterModel> contentParameter;
    private final List<ParameterModel> metadataKeyParts;
    private final SubTypesMappingContainer subTypesMappingContainer;

    public MetadataMediator(RuntimeExtensionModel extensionModel, RuntimeComponentModel componentModel)
    {
        this.componentModel = componentModel;
        this.resolverFactory = componentModel.getMetadataResolverFactory();
        this.contentParameter = getContentParameter(componentModel);
        this.metadataKeyParts = getMetadataKeyParts(componentModel);
        Optional<SubTypesModelProperty> subTypesModelProperty = extensionModel.getModelProperty(SubTypesModelProperty.class);
        this.subTypesMappingContainer = new SubTypesMappingContainer(subTypesModelProperty.isPresent() ? subTypesModelProperty.get().getSubTypesMapping()
                                                                                                       : ImmutableMap.of());
    }

    /**
     * Resolves the list of types available for the Content or Output of the associated {@link MetadataAware} Component,
     * representing them as a list of {@link MetadataKey}.
     * <p>
     * If no {@link MetadataKeyId} is present in the component's input parameters, then a {@link NullMetadataKey} is
     * returned. Otherwise, the {@link MetadataKeysResolver#getMetadataKeys} associated with the current Component will
     * be invoked to obtain the keys
     *
     * @param context current {@link MetadataContext} that will be used by the {@link MetadataKeysResolver}
     * @return Successful {@link MetadataResult} if the keys are obtained without errors
     * Failure {@link MetadataResult} when no Dynamic keys are a available or the retrieval fails for any reason
     */
    public MetadataResult<List<MetadataKey>> getMetadataKeys(MetadataContext context)
    {
        if (metadataKeyParts.isEmpty())
        {
            return success(ImmutableList.of(new NullMetadataKey()));
        }
        try
        {
            return success(resolverFactory.getKeyResolver()
                                   .getMetadataKeys(context)
                                   .stream()
                                   .collect(toList()));
        }
        catch (Exception e)
        {
            return failure(e);
        }
    }

    /**
     * Resolves the {@link ComponentMetadataDescriptor} for the associated {@link MetadataAware} Component using
     * only the static types of the Component parameters, attributes and output.
     *
     * @return An {@link ComponentMetadataDescriptor} with the Static Metadata representation
     * of the Component.
     */
    public MetadataResult<ComponentMetadataDescriptor> getMetadata()
    {
        ComponentMetadataDescriptorBuilder componentDescriptorBuilder = MetadataDescriptorBuilder.componentDescriptor(componentModel.getName())
                .withParametersDescriptor(getParametersMetadataDescriptors())
                .withOutputDescriptor(getOutputMetadataDescriptor());

        Optional<TypeMetadataDescriptor> contentDescriptor = getContentMetadataDescriptor();
        if (contentDescriptor.isPresent())
        {
            componentDescriptorBuilder.withContentDescriptor(contentDescriptor.get());
        }

        return success(componentDescriptorBuilder.build());
    }

    /**
     * Resolves the {@link ComponentMetadataDescriptor} for the associated {@link MetadataAware} Component using
     * static and dynamic resolving of the Component parameters, attributes and output.
     * <p>
     * If Component's {@link Content} parameter has a {@link MetadataContentResolver} associated or
     * its Output has a {@link MetadataOutputResolver} associated that can be used to resolve dynamic {@link MetadataType},
     * then the {@link ComponentMetadataDescriptor} will contain those Dynamic types instead of the static type declaration.
     * <p>
     * When neither {@link Content} nor Output have Dynamic types, then invoking this method is the
     * same as invoking {@link this#getMetadata()}
     *
     * @param context current {@link MetadataContext} that will be used by the {@link MetadataContentResolver}
     *                and {@link MetadataOutputResolver}
     * @param key     {@link MetadataKey} of the type which's structure has to be resolved,
     *                used both for input and output types
     * @return Successful {@link MetadataResult} if the MetadataTypes are resolved without errors
     * Failure {@link MetadataResult} when the Metadata retrieval of any element fails for any reason
     */
    public MetadataResult<ComponentMetadataDescriptor> getMetadata(MetadataContext context, MetadataKey key)
    {
        MetadataResult<OutputMetadataDescriptor> outputResult = getOutputMetadataDescriptor(context, key);
        Optional<MetadataResult<TypeMetadataDescriptor>> contentDescriptor = getContentMetadataDescriptor(context, key);

        ComponentMetadataDescriptorBuilder componentDescriptorBuilder = MetadataDescriptorBuilder.componentDescriptor(componentModel.getName())
                .withParametersDescriptor(getParametersMetadataDescriptors())
                .withOutputDescriptor(outputResult.get());

        if (!contentDescriptor.isPresent())
        {
            return outputResult.isSuccess() ? success(componentDescriptorBuilder.build()) : failure(componentDescriptorBuilder.build(), outputResult);
        }

        MetadataResult<TypeMetadataDescriptor> contentResult = contentDescriptor.get();
        componentDescriptorBuilder.withContentDescriptor(contentResult.get());

        return mergeResults(componentDescriptorBuilder.build(), outputResult, contentResult);
    }

    /**
     * For each of the Component's {@link ParameterModel} creates the corresponding {@link TypeMetadataDescriptor}
     * using only its static {@link MetadataType} and ignoring if any parameter has a dynamic type.
     *
     * @return A {@link List} containing a {@link TypeMetadataDescriptor} for each input parameter
     * using only its static {@link MetadataType} and ignoring if any parameter has a dynamic type.
     */
    private List<TypeMetadataDescriptor> getParametersMetadataDescriptors()
    {
        Stream<ParameterModel> parameters = componentModel.getParameterModels().stream();

        if (contentParameter.isPresent())
        {
            parameters = parameters.filter(p -> p != contentParameter.get());
        }

        return parameters
                .map(this::buildParameterTypeMetadataDescriptor)
                .collect(new ImmutableListCollector<>());
    }

    /**
     * Builds a {@link TypeMetadataDescriptor} from the given {@link ParameterModel}
     * an its subtypes if have any.
     *
     * @param parameterModel the {@link ParameterModel} to build the {@link TypeMetadataDescriptor}
     * @return the {@link TypeMetadataDescriptor} for the {@link ParameterModel}
     */
    private TypeMetadataDescriptor buildParameterTypeMetadataDescriptor(ParameterModel parameterModel)
    {
        List<MetadataType> subTypes = subTypesMappingContainer.getSubTypes(parameterModel.getType());
        if (!subTypes.isEmpty())
        {
            UnionTypeBuilder<?> unionTypeBuilder = BaseTypeBuilder.create(MetadataFormat.JAVA).unionType();
            subTypes.forEach(unionTypeBuilder::of);
            return typeDescriptor(parameterModel.getName()).withType(unionTypeBuilder.build()).build();
        }
        return typeDescriptor(parameterModel.getName()).withType(parameterModel.getType()).build();
    }

    /**
     * @return a {@link TypeMetadataDescriptor} representing the Component's output metadata based only
     * on its static {@link MetadataType} type and ignoring if a {@link MetadataOutputResolver} was available
     */
    private Optional<TypeMetadataDescriptor> getContentMetadataDescriptor()
    {
        return contentParameter.isPresent() ? Optional.of(typeDescriptor(contentParameter.get().getName())
                                                         .withType(contentParameter.get().getType())
                                                         .build())
                                            : Optional.empty();
    }

    /**
     * Creates a {@link TypeMetadataDescriptor} representing the Component's Content metadata using
     * the {@link MetadataContentResolver}, if one is available to resolve the {@link MetadataType}.
     * If no the Component has no Content parameter, then {@link Optional#empty()} is returned.
     *
     * @param context current {@link MetadataContext} that will be used by the {@link MetadataContentResolver}
     * @param key     {@link MetadataKey} of the type which's structure has to be resolved
     * @return Success with an {@link Optional} {@link TypeMetadataDescriptor} representing the Component's Content
     * metadata, resolved using the {@link MetadataContentResolver} if one is available to resolve its {@link MetadataType},
     * returning {@link Optional#empty()} if no Content parameter is present
     * Failure if the dynamic resolution fails for any reason.
     */
    private Optional<MetadataResult<TypeMetadataDescriptor>> getContentMetadataDescriptor(MetadataContext context, MetadataKey key)
    {
        if (!contentParameter.isPresent())
        {
            return Optional.empty();
        }

        MetadataResult<MetadataType> contentMetadataResult = getContentMetadata(context, key);
        TypeMetadataDescriptor descriptor =
                typeDescriptor(contentParameter.get().getName())
                .withType(contentMetadataResult.get())
                .build();

        return Optional.of(contentMetadataResult.isSuccess() ? success(descriptor) : failure(descriptor, contentMetadataResult));
    }

    /**
     * @return a {@link OutputMetadataDescriptor} representing the Component's output metadata based only
     * on its static {@link MetadataType} and ignoring if a {@link MetadataOutputResolver} was available
     */
    private OutputMetadataDescriptor getOutputMetadataDescriptor()
    {
        return MetadataDescriptorBuilder.outputDescriptor()
                .withAttributesType(componentModel.getAttributesType())
                .withReturnType(componentModel.getReturnType())
                .build();
    }

    /**
     * Creates an {@link OutputMetadataDescriptor} representing the Component's output metadata using
     * the {@link MetadataOutputResolver}, if one is available to resolve the output {@link MetadataType}.
     *
     * @param context current {@link MetadataContext} that will be used by the {@link MetadataContentResolver}
     * @param key     {@link MetadataKey} of the type which's structure has to be resolved
     * @return Success with an {@link OutputMetadataDescriptor} representing the Component's output metadata, resolved
     * using the {@link MetadataOutputResolver} if one is available to resolve its {@link MetadataType}.
     * Failure if the dynamic resolution fails for any reason.
     */
    private MetadataResult<OutputMetadataDescriptor> getOutputMetadataDescriptor(MetadataContext context, MetadataKey key)
    {
        MetadataResult<MetadataType> outputMetadataResult = getOutputMetadata(context, key);

        OutputMetadataDescriptor descriptor = MetadataDescriptorBuilder.outputDescriptor()
                .withReturnType(outputMetadataResult.get())
                .withAttributesType(componentModel.getAttributesType()).build();

        return outputMetadataResult.isSuccess() ? success(descriptor) : failure(descriptor, outputMetadataResult);
    }

    /**
     * Given a {@link MetadataKey} of a type and a {@link MetadataContext},
     * resolves the {@link MetadataType} of the {@link Content} parameter using
     * the {@link MetadataContentResolver} associated to the current component.
     *
     * @param context {@link MetadataContext} of the MetaData resolution
     * @param key     {@link MetadataKey} of the type which's structure has to be resolved
     * @return Success with the {@link MetadataType} of the {@link Content} parameter
     * Failure if the component has no {@link Content} parameter
     */
    private MetadataResult<MetadataType> getContentMetadata(MetadataContext context, MetadataKey key)
    {
        if (!contentParameter.isPresent())
        {
            return failure(null, "No @Content parameter found", NO_DYNAMIC_TYPE_AVAILABLE, "");
        }

        return resolveMetadataType(contentParameter.get().getType(),
                                   () -> resolverFactory.getContentResolver().getContentMetadata(context, getKeyId(key)));
    }

    /**
     * Given a {@link MetadataKey} of a type and a {@link MetadataContext},
     * resolves the {@link MetadataType} of the Components's output using
     * the {@link MetadataOutputResolver} associated to the current component.
     *
     * @param context {@link MetadataContext} of the Metadata resolution
     * @param key     {@link MetadataKey} of the type which's structure has to be resolved
     * @return the {@link MetadataType} of the components output
     */
    private MetadataResult<MetadataType> getOutputMetadata(final MetadataContext context, final MetadataKey key)
    {
        if (isVoid(componentModel))
        {
            return success(componentModel.getReturnType());
        }

        return resolveMetadataType(componentModel.getReturnType(),
                                   () -> resolverFactory.getOutputResolver().getOutputMetadata(context, getKeyId(key)));
    }

    /**
     * Uses the {@link MetadataDelegate} to resolve dynamic metadata of the component, executing internally
     * one of the {@link MetadataType}resolving components:
     * {@link MetadataContentResolver#getContentMetadata} or {@link MetadataOutputResolver#getOutputMetadata}
     *
     * @param staticType static type used as default if no dynamic type is available
     * @param delegate   Delegate which performs the final invocation to the one of the metadata resolvers
     * @return The {@link MetadataType} resolved by the delegate invocation.
     * Success if the type has been successfully fetched, Failure otherwise.
     */
    private MetadataResult<MetadataType> resolveMetadataType(MetadataType staticType, MetadataDelegate delegate)
    {
        try
        {
            MetadataType dynamicType = delegate.resolve();
            return success((dynamicType == null || isNullType(dynamicType)) ? staticType : dynamicType);
        }
        catch (Exception e)
        {
            return failure(staticType, e);
        }
    }

    /**
     * Given a {@link MetadataKey} instanciates the assosiated {@link MetadataKeyId} parameter
     * this could be a simple {@link String} if the key contains only one level
     * or a complex type with {@link MetadataKeyPart} annotated fields for a multilevel key
     *
     * @param key the key assosiated to the {@link MetadataKeyId} parameter.
     * @return the instance of the {@link MetadataKeyId} parameter.
     */
    private Object getKeyId(MetadataKey key) throws MetadataResolvingException
    {
        return metadataKeyParts.size() > 1 ? new MetadataKeyIdObjectResolver(componentModel, key).resolve() : key.getId();
    }

    private interface MetadataDelegate
    {

        MetadataType resolve() throws MetadataResolvingException, ConnectionException;
    }

}