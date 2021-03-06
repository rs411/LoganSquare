package com.bluelinelabs.logansquare.processor;

import com.bluelinelabs.logansquare.Constants;
import com.bluelinelabs.logansquare.JsonMapper;
import com.bluelinelabs.logansquare.LoganSquare;
import com.bluelinelabs.logansquare.ParameterizedType;
import com.bluelinelabs.logansquare.processor.type.Type;
import com.bluelinelabs.logansquare.processor.type.Type.ClassNameObjectMapper;
import com.bluelinelabs.logansquare.processor.type.field.FieldType;
import com.bluelinelabs.logansquare.processor.type.field.ParameterizedTypeField;
import com.bluelinelabs.logansquare.processor.type.field.TypeConverterFieldType;
import com.bluelinelabs.logansquare.typeconverters.TypeConverter;
import com.bluelinelabs.logansquare.util.SimpleArrayMap;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeVariable;

public class ObjectMapperInjector {

    public static final String PARENT_OBJECT_MAPPER_VARIABLE_NAME = "parentObjectMapper";
    public static final String JSON_PARSER_VARIABLE_NAME = "jsonParser";
    public static final String JSON_GENERATOR_VARIABLE_NAME = "jsonGenerator";

    private final JsonObjectHolder mJsonObjectHolder;

    public ObjectMapperInjector(JsonObjectHolder jsonObjectHolder) {
        mJsonObjectHolder = jsonObjectHolder;
    }

    public String getJavaClassFile() {
        try {
            return JavaFile.builder(mJsonObjectHolder.packageName, getTypeSpec()).build().toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private TypeSpec getTypeSpec() {
        TypeSpec.Builder builder = TypeSpec.classBuilder(mJsonObjectHolder.injectedClassName).addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        builder.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "\"unsafe,unchecked\"").build());

        builder.superclass(ParameterizedTypeName.get(ClassName.get(JsonMapper.class), mJsonObjectHolder.objectTypeName));

        for (TypeParameterElement typeParameterElement : mJsonObjectHolder.typeParameters) {
            builder.addTypeVariable(TypeVariableName.get((TypeVariable) typeParameterElement.asType()));
        }

        if (mJsonObjectHolder.hasParentClass()) {
            FieldSpec.Builder parentMapperBuilder;

            if (mJsonObjectHolder.parentTypeParameters.size() == 0) {
                parentMapperBuilder = FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(JsonMapper.class), mJsonObjectHolder.parentTypeName), PARENT_OBJECT_MAPPER_VARIABLE_NAME)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.mapperFor($T.class)", LoganSquare.class, mJsonObjectHolder.parentTypeName);
            } else {
                parentMapperBuilder = FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(JsonMapper.class), mJsonObjectHolder.getParameterizedParentTypeName()), PARENT_OBJECT_MAPPER_VARIABLE_NAME)
                        .addModifiers(Modifier.PRIVATE);

                if (mJsonObjectHolder.typeParameters.size() == 0) {
                    parentMapperBuilder.initializer("$T.mapperFor(new $T<$T>() { })", LoganSquare.class, ParameterizedType.class, mJsonObjectHolder.getParameterizedParentTypeName());
                }
            }

            builder.addField(parentMapperBuilder.build());
        }

        boolean isUpdatable = !TextUtils.isEmpty(mJsonObjectHolder.getObjectByKeyCallback);

        if (isUpdatable) {
            addUpdateFieldFlags(builder);
        }

        // TypeConverters could be expensive to create, so just use one per class
        Set<ClassName> typeConvertersUsed = new HashSet<>();
        for (JsonFieldHolder fieldHolder : mJsonObjectHolder.fieldMap.values()) {
            if (fieldHolder.type instanceof TypeConverterFieldType) {
                typeConvertersUsed.add(((TypeConverterFieldType) fieldHolder.type).getTypeConverterClassName());
            }
        }
        for (ClassName typeConverter : typeConvertersUsed) {
            builder.addField(FieldSpec.builder(typeConverter, getStaticFinalTypeConverterVariableName(typeConverter))
                    .addModifiers(Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T()", typeConverter)
                    .build());
        }

        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        List<String> createdJsonMappers = new ArrayList<>();

        if (mJsonObjectHolder.typeParameters.size() > 0) {
            constructorBuilder.addParameter(ClassName.get(ParameterizedType.class), "type");
            constructorBuilder.addStatement("partialMappers.put(type, this)");

            for (TypeParameterElement typeParameterElement : mJsonObjectHolder.typeParameters) {
                final String typeName = typeParameterElement.getSimpleName().toString();
                final String typeArgumentName = typeName + "Type";
                final String jsonMapperVariableName = getJsonMapperVariableNameForTypeParameter(typeName);

                if (!createdJsonMappers.contains(jsonMapperVariableName)) {
                    createdJsonMappers.add(jsonMapperVariableName);

                    // Add a JsonMapper reference
                    builder.addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(JsonMapper.class), TypeVariableName.get(typeName)), jsonMapperVariableName)
                            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                            .build());

                    constructorBuilder.addParameter(ClassName.get(ParameterizedType.class), typeArgumentName);
                    constructorBuilder.addStatement("$L = $T.mapperFor($L, partialMappers)", jsonMapperVariableName, LoganSquare.class, typeArgumentName);
                }
            }
            constructorBuilder.addParameter(ParameterizedTypeName.get(ClassName.get(SimpleArrayMap.class), ClassName.get(ParameterizedType.class), ClassName.get(JsonMapper.class)), "partialMappers");
        }

        for (JsonFieldHolder jsonFieldHolder : mJsonObjectHolder.fieldMap.values()) {
            if (jsonFieldHolder.type instanceof ParameterizedTypeField) {
                final String jsonMapperVariableName = getJsonMapperVariableNameForTypeParameter(((ParameterizedTypeField) jsonFieldHolder.type).getParameterName());

                if (!createdJsonMappers.contains(jsonMapperVariableName)) {
                    ParameterizedTypeName parameterizedType = ParameterizedTypeName.get(ClassName.get(JsonMapper.class), jsonFieldHolder.type.getTypeName());

                    createdJsonMappers.add(jsonMapperVariableName);
                    builder.addField(FieldSpec.builder(parameterizedType, jsonMapperVariableName)
                            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                            .build());

                    String typeName = jsonMapperVariableName + "Type";
                    constructorBuilder.addStatement("$T $L = new $T<$T>() { }", ParameterizedType.class, typeName, ParameterizedType.class, jsonFieldHolder.type.getTypeName());

                    if (mJsonObjectHolder.typeParameters.size() > 0) {
                        constructorBuilder.beginControlFlow("if ($L.equals(type))", typeName);
                        constructorBuilder.addStatement("$L = ($T)this", jsonMapperVariableName, JsonMapper.class);
                        constructorBuilder.nextControlFlow("else");
                        constructorBuilder.addStatement("$L = $T.mapperFor($L, partialMappers)", jsonMapperVariableName, LoganSquare.class, typeName);
                        constructorBuilder.endControlFlow();
                    } else {
                        constructorBuilder.addStatement("$L = $T.mapperFor($L)", jsonMapperVariableName, LoganSquare.class, typeName);
                    }
                }
            }
        }

        if (createdJsonMappers.size() > 0) {
            if (mJsonObjectHolder.hasParentClass()) {
                constructorBuilder.addStatement("$L = $T.mapperFor(new $T<$T>() { })", PARENT_OBJECT_MAPPER_VARIABLE_NAME, LoganSquare.class, ParameterizedType.class, mJsonObjectHolder.getParameterizedParentTypeName());
            }
            builder.addMethod(constructorBuilder.build());
        }

        builder.addMethod(getParseMethod(isUpdatable, false));
        if (isUpdatable) {
            builder.addMethod(getParseMethod(true, true));
        }
        if (mJsonObjectHolder.inheritsFromParent) {
            builder.addMethod(getEndParseMethod(isUpdatable));
        }
        builder.addMethod(getParseFieldMethod(false));
        if (isUpdatable) {
            builder.addMethod(getParseFieldMethod(true));
            builder.addMethod(MethodSpec.methodBuilder("holder")
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .addStatement("return new DataHolder()")
                    .returns(TypeName.OBJECT)
                    .build());
        }
        builder.addMethod(getSerializeMethod());
        addUsedJsonMapperVariables(builder);
        addUsedTypeConverterMethods(builder);
        return builder.build();
    }

    private MethodSpec getParseMethod(boolean isUpdatable, boolean isSecondary) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("parse")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(mJsonObjectHolder.objectTypeName)
                .addParameter(JsonParser.class, JSON_PARSER_VARIABLE_NAME);
        if (isSecondary) {
            builder.addParameter(TypeName.OBJECT, "dataHolderObject");
        }
        builder
                .addException(IOException.class);
        if (isSecondary) {
            builder.addStatement("DataHolder dataHolder = (DataHolder) dataHolderObject")
                    .beginControlFlow("if(dataHolder == null)")
                    .addStatement("dataHolder = new DataHolder()")
                    .endControlFlow();
        }
        if (!mJsonObjectHolder.isAbstractClass) {
            builder.addStatement("$T instance = new $T()", mJsonObjectHolder.objectTypeName, mJsonObjectHolder.objectTypeName)
                    .beginControlFlow("if ($L.getCurrentToken() == null)", JSON_PARSER_VARIABLE_NAME)
                    .addStatement("$L.nextToken()", JSON_PARSER_VARIABLE_NAME)
                    .endControlFlow()
                    .beginControlFlow("if ($L.getCurrentToken() != $T.START_OBJECT)", JSON_PARSER_VARIABLE_NAME, JsonToken.class)
                    .addStatement("$L.skipChildren()", JSON_PARSER_VARIABLE_NAME)
                    .addStatement("return null")
                    .endControlFlow();
            if (isUpdatable && !isSecondary) {
                builder.addStatement("DataHolder dataHolder = new DataHolder()");
            }
            builder.beginControlFlow("while ($L.nextToken() != $T.END_OBJECT)", JSON_PARSER_VARIABLE_NAME, JsonToken.class)
                    .addStatement("String fieldName = $L.getCurrentName()", JSON_PARSER_VARIABLE_NAME)
                    .addStatement("$L.nextToken()", JSON_PARSER_VARIABLE_NAME)
                    .addStatement("parseField(instance, fieldName, $L" + (isUpdatable
                                                                          ? ", dataHolder"
                                                                          : "") + ")", JSON_PARSER_VARIABLE_NAME)
                    .addStatement("$L.skipChildren()", JSON_PARSER_VARIABLE_NAME)
                    .endControlFlow();
            if (!mJsonObjectHolder.inheritsFromParent) {
                endParseMethod(builder, isUpdatable);
            }
            builder.addStatement("return instance");
        } else {
            builder.addStatement("return null");
        }

        return builder.build();
    }

    private MethodSpec getEndParseMethod(boolean isUpdatable) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("endParse")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Object.class, "parentInstance")
                .addParameter(mJsonObjectHolder.objectTypeName, "instance")
                .addParameter(TypeName.OBJECT, "dataObject")
                .addException(IOException.class)
                .returns(TypeName.VOID)
                .addStatement("DataHolder dataHolder = (DataHolder) dataObject")
                .beginControlFlow("if(dataHolder == null)")
                .addStatement("dataHolder = new DataHolder()")
                .endControlFlow();

        if (mJsonObjectHolder.onInheritCallback != null) {
            ExecutableElement inheritCallback = mJsonObjectHolder.onInheritCallback;
            String parentType = inheritCallback.getParameters().get(0).asType().toString();
            if ("Object".equals(parentType)) {
                parentType = "";
            } else {
                parentType = '(' + parentType + ") ";
            }
            builder.addStatement("instance.$L(" + parentType + "parentInstance)", inheritCallback.getSimpleName().toString());
        }
        endParseMethod(builder, isUpdatable);
        return builder.build();
    }

    private void endParseMethod(MethodSpec.Builder builder, boolean isUpdatable) {
        if (isUpdatable) {
            JsonFieldHolder keyField = null;
            for (JsonFieldHolder holder : mJsonObjectHolder.fieldMap.values()) {
                if (holder.isKey()) {
                    keyField = holder;
                    break;
                }
            }
            if (keyField == null) {
                throw new RuntimeException("Missing @JsonField with @JsonKey annotation for getByKey() method");
            }

            StringBuilder stringBuilder = new StringBuilder(
                    "$T sourceInstance = instance.$L(instance.");
            if (keyField.hasGetter()) {
                stringBuilder.append(keyField.getterMethod);
                stringBuilder.append("()");
            } else {
                stringBuilder.append(keyField.fieldName[0]);
            }
            stringBuilder.append(")");

            builder.addStatement(stringBuilder.toString(), mJsonObjectHolder.objectTypeName,
                    mJsonObjectHolder.getObjectByKeyCallback)
                    .beginControlFlow("if(sourceInstance != null)");
            for (Map.Entry<String, JsonFieldHolder> entry : mJsonObjectHolder.fieldMap.entrySet()) {
                String fieldName = entry.getKey();
                JsonFieldHolder fieldHolder = entry.getValue();
                if (fieldHolder.shouldParse) {
                    builder.beginControlFlow("if(!dataHolder." + getIsFieldSetName(fieldName) + ")");

                    String getter;
                    if (fieldHolder.hasGetter()) {
                        getter = "sourceInstance." + fieldHolder.getterMethod + "()";
                    } else {
                        getter = "sourceInstance." + fieldName;
                    }
                    if (fieldHolder.hasSetter()) {
                        builder.addStatement("instance.$L($L)", fieldHolder.setterMethod, getter);
                    } else {
                        builder.addStatement("instance.$L = $L", fieldName, getter);
                    }
                    builder.endControlFlow();
                }
            }
            builder.endControlFlow();
        }
        for (Map.Entry<String, JsonFieldHolder> entry : mJsonObjectHolder.fieldMap.entrySet()) {

            JsonFieldHolder fieldHolder = entry.getValue();
            if (fieldHolder.inherits) {
                String fieldName = entry.getKey(), getter;
                if (fieldHolder.hasGetter()) {
                    getter = "instance." + fieldHolder.getterMethod + "()";
                } else {
                    getter = "instance." + fieldName;
                }

                if (fieldHolder.type != null) {
                    String mapperName = getMapperVariableName(fieldHolder.type.getTypeName() + Constants.MAPPER_CLASS_SUFFIX),
                            dataHolderName = getDataHolderName(entry.getKey());
                    builder.beginControlFlow("if(" + getter + " != null)")
                            .addStatement("$L.endParse(instance, " + getter + ", dataHolder.$L)", mapperName, dataHolderName)
                            .endControlFlow();
                }
            }
        }
        if (!TextUtils.isEmpty(mJsonObjectHolder.onCompleteCallback)) {
            builder.addStatement("instance.$L()", mJsonObjectHolder.onCompleteCallback);
        }
    }

    private MethodSpec getParseFieldMethod(boolean isUpdatable) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("parseField")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(mJsonObjectHolder.objectTypeName, "instance")
                .addParameter(String.class, "fieldName")
                .addParameter(JsonParser.class, JSON_PARSER_VARIABLE_NAME);
        if (isUpdatable) {
            builder.addParameter(ClassName.get(mJsonObjectHolder.packageName + "." + mJsonObjectHolder.injectedClassName, "DataHolder"), "dataHolder");
        } else {
            builder.addAnnotation(Override.class);
        }
        builder.addException(IOException.class);

        int parseFieldLines = addParseFieldLines(builder, isUpdatable);

        if (mJsonObjectHolder.hasParentClass()) {
            if (parseFieldLines > 0) {
                builder.nextControlFlow("else");
                builder.addStatement("$L.parseField(instance, fieldName, $L)", PARENT_OBJECT_MAPPER_VARIABLE_NAME, JSON_PARSER_VARIABLE_NAME);
            } else {
                builder.addStatement("$L.parseField(instance, fieldName, $L)", PARENT_OBJECT_MAPPER_VARIABLE_NAME, JSON_PARSER_VARIABLE_NAME);
            }
        }

        if (parseFieldLines > 0) {
            builder.endControlFlow();
        }

        return builder.build();
    }

    private MethodSpec getSerializeMethod() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("serialize")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(mJsonObjectHolder.objectTypeName, "object")
                .addParameter(JsonGenerator.class, JSON_GENERATOR_VARIABLE_NAME)
                .addParameter(boolean.class, "writeStartAndEnd")
                .addException(IOException.class);

        insertSerializeStatements(builder);

        return builder.build();
    }

    private void insertSerializeStatements(MethodSpec.Builder builder) {
        if (!TextUtils.isEmpty(mJsonObjectHolder.preSerializeCallback)) {
            builder.addStatement("object.$L()", mJsonObjectHolder.preSerializeCallback);
        }

        builder
                .beginControlFlow("if (writeStartAndEnd)")
                .addStatement("$L.writeStartObject()", JSON_GENERATOR_VARIABLE_NAME)
                .endControlFlow();

        List<String> processedFields = new ArrayList<>(mJsonObjectHolder.fieldMap.size());
        for (Map.Entry<String, JsonFieldHolder> entry : mJsonObjectHolder.fieldMap.entrySet()) {
            JsonFieldHolder fieldHolder = entry.getValue();

            if (fieldHolder.shouldSerialize) {
                String getter;
                if (fieldHolder.hasGetter()) {
                    getter = "object." + fieldHolder.getterMethod + "()";
                } else {
                    getter = "object." + entry.getKey();
                }

                fieldHolder.type.serialize(builder, 1, fieldHolder.fieldName[0], processedFields, getter, true, true, mJsonObjectHolder.serializeNullObjects, mJsonObjectHolder.serializeNullCollectionElements);
            }
        }

        if (mJsonObjectHolder.hasParentClass()) {
            builder.addStatement("$L.serialize(object, $L, false)", PARENT_OBJECT_MAPPER_VARIABLE_NAME, JSON_GENERATOR_VARIABLE_NAME);
        }

        builder
                .beginControlFlow("if (writeStartAndEnd)")
                .addStatement("$L.writeEndObject()", JSON_GENERATOR_VARIABLE_NAME)
                .endControlFlow();
    }

    private void addUpdateFieldFlags(TypeSpec.Builder builder) {
        TypeSpec.Builder holderBuilder = TypeSpec.classBuilder("DataHolder")
                .addModifiers(Modifier.PUBLIC)
                .addModifiers(Modifier.STATIC);
        for (Map.Entry<String, JsonFieldHolder> entry : mJsonObjectHolder.fieldMap.entrySet()) {
            String key = entry.getKey();
            JsonFieldHolder holder = entry.getValue();
            if (holder.shouldParse) {
                holderBuilder.addField(FieldSpec.builder(TypeName.BOOLEAN, getIsFieldSetName(key), Modifier.PRIVATE).build());
                if (holder.inherits) {
                    holderBuilder.addField(Object.class, getDataHolderName(key));
                }
            }
        }
        TypeSpec dataHolderTypeSpec = holderBuilder.build();
        builder.addType(dataHolderTypeSpec);
    }

    private String getIsFieldSetName(String name) {
        if (name.length() > 0) {
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        return "is" + name + "Set";
    }

    private String getDataHolderName(String name) {
        return name + "DataHolder";
    }

    private int addParseFieldLines(MethodSpec.Builder builder, boolean isUpdatable) {
        int entryCount = 0;
        for (Map.Entry<String, JsonFieldHolder> entry : mJsonObjectHolder.fieldMap.entrySet()) {
            JsonFieldHolder fieldHolder = entry.getValue();

            if (fieldHolder.shouldParse) {
                List<Object> args = new ArrayList<>();
                StringBuilder ifStatement = new StringBuilder();
                boolean isFirst = true;
                for (String fieldName : fieldHolder.fieldName) {
                    if (isFirst) {
                        isFirst = false;
                    } else {
                        ifStatement.append(" || ");
                    }
                    ifStatement.append("$S.equals(fieldName)");
                    args.add(fieldName);
                }

                if (entryCount == 0) {
                    builder.beginControlFlow("if (" + ifStatement.toString() + ")", args.toArray(new Object[args.size()]));
                } else {
                    builder.nextControlFlow("else if (" + ifStatement.toString() + ")", args.toArray(new Object[args.size()]));
                }

                String setter;
                Object[] stringFormatArgs;
                if (fieldHolder.hasSetter()) {
                    setter = "instance.$L($L)";
                    stringFormatArgs = new Object[]{fieldHolder.setterMethod};
                } else {
                    setter = "instance.$L = $L";
                    stringFormatArgs = new Object[]{entry.getKey()};
                }

                if (fieldHolder.type != null) {
                    setFieldHolderJsonMapperVariableName(fieldHolder.type);
                    if (fieldHolder.inherits && isUpdatable) {
                        String mapperName = getMapperVariableName(fieldHolder.type.getTypeName() + Constants.MAPPER_CLASS_SUFFIX),
                                dataHolderName = getDataHolderName(entry.getKey());
                        builder.addStatement("dataHolder.$L = $L.holder()", dataHolderName, mapperName)
                                .addStatement(FieldType.replaceLastLiteral(setter, "$L.parse($L, dataHolder.$L)"), stringFormatArgs[0], mapperName, JSON_PARSER_VARIABLE_NAME, dataHolderName);
                    } else {
                        fieldHolder.type.parse(builder, 1, setter, stringFormatArgs);
                    }
                    if (isUpdatable) {
                        builder.addStatement("dataHolder." + getIsFieldSetName(entry.getKey()) + " = true");
                    }
                }

                entryCount++;
            }
        }
        return entryCount;
    }

    private void addUsedJsonMapperVariables(TypeSpec.Builder builder) {
        Set<ClassNameObjectMapper> usedJsonObjectMappers = new HashSet<>();

        for (JsonFieldHolder holder : mJsonObjectHolder.fieldMap.values()) {
            if (holder.type == null) {
                throw new NullPointerException("No type for " + holder.fieldName[0] + " in " + mJsonObjectHolder.objectTypeName.toString());
            }
            usedJsonObjectMappers.addAll(holder.type.getUsedJsonObjectMappers());
        }

        for (ClassNameObjectMapper usedJsonObjectMapper : usedJsonObjectMappers) {
            builder.addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(JsonMapper.class), usedJsonObjectMapper.className), getMapperVariableName(usedJsonObjectMapper.objectMapper))
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.mapperFor($T.class)", LoganSquare.class, usedJsonObjectMapper.className)
                    .build()
            );
        }
    }

    private void addUsedTypeConverterMethods(TypeSpec.Builder builder) {
        Set<TypeName> usedTypeConverters = new HashSet<>();

        for (JsonFieldHolder holder : mJsonObjectHolder.fieldMap.values()) {
            usedTypeConverters.addAll(holder.type.getUsedTypeConverters());
        }

        for (TypeName usedTypeConverter : usedTypeConverters) {
            final String variableName = getTypeConverterVariableName(usedTypeConverter);
            builder.addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(TypeConverter.class), usedTypeConverter), variableName)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .build()
            );

            builder.addMethod(MethodSpec.methodBuilder(getTypeConverterGetter(usedTypeConverter))
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .returns(ParameterizedTypeName.get(ClassName.get(TypeConverter.class), usedTypeConverter))
                    .beginControlFlow("if ($L == null)", variableName)
                    .addStatement("$L = $T.typeConverterFor($T.class)", variableName, LoganSquare.class, usedTypeConverter)
                    .endControlFlow()
                    .addStatement("return $L", variableName)
                    .build()
            );
        }
    }

    private String getJsonMapperVariableNameForTypeParameter(String typeName) {
        String typeNameHash = "" + typeName.hashCode();
        typeNameHash = typeNameHash.replaceAll("-", "m");
        return "m" + typeNameHash + "ClassJsonMapper";
    }

    public static String getStaticFinalTypeConverterVariableName(TypeName typeName) {
        return typeName.toString().replaceAll("\\.", "_").replaceAll("\\$", "_").toUpperCase();
    }

    public static String getTypeConverterVariableName(TypeName typeName) {
        return typeName.toString().replaceAll("\\.", "_").replaceAll("\\$", "_") + "_type_converter";
    }

    private void setFieldHolderJsonMapperVariableName(Type type) {
        if (type instanceof ParameterizedTypeField) {
            ParameterizedTypeField parameterizedType = (ParameterizedTypeField) type;
            parameterizedType.setJsonMapperVariableName(getJsonMapperVariableNameForTypeParameter(parameterizedType.getParameterName()));
        }

        for (Type subType : type.parameterTypes) {
            setFieldHolderJsonMapperVariableName(subType);
        }
    }

    public static String getMapperVariableName(Class cls) {
        return getMapperVariableName(cls.getCanonicalName());
    }

    public static String getMapperVariableName(String fullyQualifiedClassName) {
        return fullyQualifiedClassName.replaceAll("\\.", "_").replaceAll("\\$", "_").toUpperCase();
    }

    public static String getTypeConverterGetter(TypeName typeName) {
        return "get" + getTypeConverterVariableName(typeName);
    }

}
