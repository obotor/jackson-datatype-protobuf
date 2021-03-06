package com.hubspot.jackson.datatype.protobuf;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.PropertyNamingStrategy.PropertyNamingStrategyBase;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.type.SimpleType;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.ExtensionRegistry.ExtensionInfo;
import com.google.protobuf.GeneratedMessage.ExtendableMessageOrBuilder;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

public class ProtobufDeserializer<T extends Message> extends StdDeserializer<MessageOrBuilder> {
  private final T defaultInstance;
  private final boolean build;
  @SuppressFBWarnings(value="SE_BAD_FIELD")
  private final ExtensionRegistryWrapper extensionRegistry;
  @SuppressFBWarnings(value="SE_BAD_FIELD")
  private final Map<FieldDescriptor, JsonDeserializer<Object>> deserializerCache;

  public ProtobufDeserializer(Class<T> messageType, boolean build) throws JsonMappingException {
    this(messageType, build, ExtensionRegistryWrapper.empty());
  }

  @SuppressWarnings("unchecked")
  public ProtobufDeserializer(Class<T> messageType, boolean build,
                              ExtensionRegistryWrapper extensionRegistry) throws JsonMappingException {
    super(messageType);

    try {
      this.defaultInstance = (T) messageType.getMethod("getDefaultInstance").invoke(null);
    } catch (Exception e) {
      throw new JsonMappingException("Unable to get default instance for type " + messageType, e);
    }

    this.build = build;
    this.extensionRegistry = extensionRegistry;
    this.deserializerCache = new ConcurrentHashMap<>();
  }

  @Override
  public MessageOrBuilder deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    Message.Builder builder = defaultInstance.newBuilderForType();

    populate(builder, parser, context);

    if (build) {
      return builder.build();
    } else {
      return builder;
    }
  }

  private void populate(Message.Builder builder, JsonParser parser, DeserializationContext context)
          throws IOException {
    JsonToken token = parser.getCurrentToken();
    if (token == JsonToken.START_ARRAY) {
      token = parser.nextToken();
    }

    switch (token) {
      case END_OBJECT:
        return;
      case START_OBJECT:
        token = parser.nextToken();
        if (token == JsonToken.END_OBJECT) {
          return;
        }
        break;
      default:
        break; // make findbugs happy
    }

    final Descriptor descriptor = builder.getDescriptorForType();
    final Map<String, FieldDescriptor> fieldLookup = buildFieldLookup(descriptor, context);
    final Map<String, ExtensionInfo> extensionLookup;
    if (builder instanceof ExtendableMessageOrBuilder<?>) {
      extensionLookup = buildExtensionLookup(descriptor, context);
    } else {
      extensionLookup = Collections.emptyMap();
    }

    do {
      if (!token.equals(JsonToken.FIELD_NAME)) {
        throw context.wrongTokenException(parser, JsonToken.FIELD_NAME, "");
      }

      String name = parser.getCurrentName();
      FieldDescriptor field = fieldLookup.get(name);
      Message defaultInstance = null;
      if (field == null) {
        ExtensionInfo extensionInfo = extensionLookup.get(name);
        if (extensionInfo != null) {
          field = extensionInfo.descriptor;
          defaultInstance = extensionInfo.defaultInstance;
        }
      }

      if (field == null) {
        if (!context.handleUnknownProperty(parser, this, builder, name)) {
          context.reportUnknownProperty(builder, name, this);
        }

        parser.nextToken();
        parser.skipChildren();
        continue;
      }

      parser.nextToken();
      setField(builder, field, defaultInstance, parser, context);
    } while ((token = parser.nextToken()) != JsonToken.END_OBJECT);
  }

  private Map<String, FieldDescriptor> buildFieldLookup(Descriptor descriptor, DeserializationContext context) {
    PropertyNamingStrategyBase namingStrategy =
            new PropertyNamingStrategyWrapper(context.getConfig().getPropertyNamingStrategy());

    Map<String, FieldDescriptor> fieldLookup = new HashMap<>();
    for (FieldDescriptor field : descriptor.getFields()) {
      fieldLookup.put(namingStrategy.translate(field.getName()), field);
    }

    return fieldLookup;
  }

  private Map<String, ExtensionInfo> buildExtensionLookup(Descriptor descriptor, DeserializationContext context) {
    PropertyNamingStrategyBase namingStrategy =
            new PropertyNamingStrategyWrapper(context.getConfig().getPropertyNamingStrategy());

    Map<String, ExtensionInfo> extensionLookup = new HashMap<>();
    for (ExtensionInfo extensionInfo : extensionRegistry.findExtensionsByDescriptor(descriptor)) {
      extensionLookup.put(namingStrategy.translate(extensionInfo.descriptor.getName()), extensionInfo);
    }

    return extensionLookup;
  }

  private void setField(Message.Builder builder, FieldDescriptor field, Message defaultInstance, JsonParser parser,
                        DeserializationContext context) throws IOException {
    Object value = readValue(builder, field, defaultInstance, parser, context);

    if (value != null) {
      if (field.isRepeated()) {
        if (value instanceof Iterable) {
          for (Object subValue : (Iterable<?>) value) {
            builder.addRepeatedField(field, subValue);
          }
        } else if (context.isEnabled(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)) {
          builder.addRepeatedField(field, value);
        } else {
          throw mappingException(field, context);
        }
      } else {
        builder.setField(field, value);
      }
    }
  }

  private Object getNullValue(JavaType type, Object nullValue, DeserializationContext context) throws JsonProcessingException {
    if (type.isPrimitive() && context.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)) {
      throw context.mappingException("Can not map JSON null into type %s"
          + " (set DeserializationConfig.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES to 'false' to allow) -- "
          + type.getRawClass().getName());
    }
    return nullValue;
  }

  private Object readValue(Message.Builder builder, FieldDescriptor field, Message defaultInstance, JsonParser parser,
                           DeserializationContext context) throws IOException {
    final Object value;

    if (parser.getCurrentToken() == JsonToken.START_ARRAY) {
      if (field.isRepeated()) {
        return readArray(builder, field, defaultInstance, parser, context);
      } else {
        throw mappingException(field, context);
      }
    }

    switch (field.getJavaType()) {
      case INT:
        value = _parseInteger(parser, context);

        if (value == null) {
          getNullValue(SimpleType.construct(Integer.TYPE), 0, context);
        }
        break;
      case LONG:
        value = _parseLong(parser, context);

        if (value == null) {
          getNullValue(SimpleType.construct(Long.TYPE), 0L, context);
        }
        break;
      case FLOAT:
        value = _parseFloat(parser, context);

        if (value == null) {
          getNullValue(SimpleType.construct(Float.TYPE), 0.0f, context);
        }
        break;
      case DOUBLE:
        value = _parseDouble(parser, context);

        if (value == null) {
          getNullValue(SimpleType.construct(Double.TYPE), 0.0d, context);
        }
        break;
      case BOOLEAN:
        value = _parseBoolean(parser, context);

        if (value == null) {
          getNullValue(SimpleType.construct(Boolean.TYPE), false, context);
        }
        break;
      case STRING:
        switch (parser.getCurrentToken()) {
          case VALUE_STRING:
            value = parser.getText();
            break;
          case VALUE_NULL:
            value = null;
            break;
          default:
            value = _parseString(parser, context);
        }
        break;
      case BYTE_STRING:
        switch (parser.getCurrentToken()) {
          case VALUE_STRING:
            value = ByteString.copyFrom(context.getBase64Variant().decode(parser.getText()));
            break;
          case VALUE_NULL:
            value = null;
            break;
          default:
            throw mappingException(field, context);
        }
        break;
      case ENUM:
        switch (parser.getCurrentToken()) {
          case VALUE_STRING:
            value = field.getEnumType().findValueByName(parser.getText());

            if (value == null && !ignorableEnum(parser.getText().trim(), context)) {
              throw context.weirdStringException(parser.getText(), field.getEnumType().getClass(),
                      "value not one of declared Enum instance names");
            }
            break;
          case VALUE_NUMBER_INT:
            if (allowNumbersForEnums(context)) {
              value = field.getEnumType().findValueByNumber(parser.getIntValue());

              if (value == null && !ignoreUnknownEnums(context)) {
                throw context.weirdNumberException(parser.getIntValue(), field.getEnumType().getClass(),
                        "index value outside legal index range " + indexRange(field.getEnumType()));
              }
            } else {
              throw context.mappingException("Not allowed to deserialize Enum value out of JSON number " +
                      "(disable DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS to allow)");
            }
            break;
          case VALUE_NULL:
            value = null;
            break;
          default:
            throw mappingException(field, context);
        }
        break;
      case MESSAGE:
        switch (parser.getCurrentToken()) {
          case START_OBJECT:
            JsonDeserializer<Object> deserializer = deserializerCache.get(field);
            if (deserializer == null) {
              final Class<?> subType;
              if (defaultInstance == null) {
                Message.Builder subBuilder = builder.newBuilderForField(field);
                subType = subBuilder.getDefaultInstanceForType().getClass();
              } else {
                subType = defaultInstance.getClass();
              }

              JavaType type = SimpleType.construct(subType);
              deserializer = context.findContextualValueDeserializer(type, null);
              deserializerCache.put(field, deserializer);
            }

            value = deserializer.deserialize(parser, context);
            break;
          case VALUE_NULL:
            value = null;
            break;
          default:
            throw mappingException(field, context);
        }
        break;
      default:
        throw mappingException(field, context);
    }

    return value;
  }

  private List<Object> readArray(Message.Builder builder, FieldDescriptor field, Message defaultInstance, JsonParser parser,
                                 DeserializationContext context) throws IOException {
    List<Object> values = Lists.newArrayList();
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      Object value = readValue(builder, field, defaultInstance, parser, context);

      if (value != null) {
        values.add(value);
      }
    }
    return values;
  }

  private static boolean ignorableEnum(String value, DeserializationContext context) {
    return (acceptEmptyStringAsNull(context) && value.length() == 0) || ignoreUnknownEnums(context);
  }

  private static boolean acceptEmptyStringAsNull(DeserializationContext context) {
    return context.isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
  }

  private static boolean allowNumbersForEnums(DeserializationContext context) {
    return !context.isEnabled(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
  }

  private static boolean ignoreUnknownEnums(DeserializationContext context) {
    return context.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
  }

  private static String indexRange(EnumDescriptor field) {
    List<Integer> indices = Lists.transform(field.getValues(), new Function<EnumValueDescriptor, Integer>() {
      @Override
      public Integer apply(@Nonnull EnumValueDescriptor value) {
        return value.getIndex();
      }
    });

    // Guava returns non-modifiable list
    indices = Lists.newArrayList(indices);

    Collections.sort(indices);

    return "[" + Joiner.on(',').join(indices) + "]";
  }

  private static JsonMappingException mappingException(FieldDescriptor field, DeserializationContext context)
          throws IOException {
    JsonToken token = context.getParser().getCurrentToken();
    String message = "Can not deserialize instance of " + field.getJavaType() + " out of " + token + " token";
    throw context.mappingException(message);
  }
}
