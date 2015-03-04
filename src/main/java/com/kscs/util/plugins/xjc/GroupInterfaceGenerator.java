/*
 * MIT License
 *
 * Copyright (c) 2014 Klemm Software Consulting, Mirko Klemm
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.kscs.util.plugins.xjc;

import javax.xml.bind.JAXB;
import javax.xml.namespace.QName;
import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import java.beans.PropertyVetoException;
import java.io.StringWriter;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import com.kscs.util.jaxb._interface.Interface;
import com.kscs.util.jaxb._interface.Interfaces;
import com.kscs.util.plugins.xjc.base.AbstractXSFunction;
import com.sun.codemodel.ClassType;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JDocComment;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;
import com.sun.codemodel.util.JavadocEscapeWriter;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.FieldOutline;
import com.sun.tools.xjc.outline.PackageOutline;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BIProperty;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BindInfo;
import com.sun.xml.xsom.*;
import com.sun.xml.xsom.impl.util.SchemaWriter;
import com.sun.xml.xsom.visitor.XSFunction;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * @author mirko 2014-05-29
 */
class GroupInterfaceGenerator {
	private static final XSFunction<Boolean> IS_FIXED_FUNC = new AbstractXSFunction<Boolean>() {

		@Override
		public Boolean attributeDecl(final XSAttributeDecl decl) {
			return decl.getFixedValue() != null;
		}

		@Override
		public Boolean attributeUse(final XSAttributeUse use) {
			return use.getFixedValue() != null || use.getDecl().getFixedValue() != null;
		}

		@Override
		public Boolean elementDecl(final XSElementDecl decl) {
			return decl.getFixedValue() != null;
		}
	};
	private final XSFunction<String> nameFunc = new AbstractXSFunction<String>() {

		@Override
		public String attributeDecl(final XSAttributeDecl decl) {
			final String customName = getCustomPropertyName(decl);
			return customName == null ? GroupInterfaceGenerator.this.apiConstructs.outline.getModel().getNameConverter().toPropertyName(decl.getName()) : customName;
		}

		@Override
		public String attributeUse(final XSAttributeUse use) {
			String customName = getCustomPropertyName(use);
			customName = customName == null ? getCustomPropertyName(use.getDecl()) : customName;
			return customName == null ? GroupInterfaceGenerator.this.apiConstructs.outline.getModel().getNameConverter().toPropertyName(use.getDecl().getName()) : customName;
		}

		@Override
		public String elementDecl(final XSElementDecl decl) {
			final String customName = getCustomPropertyName(decl);
			return customName == null ? GroupInterfaceGenerator.this.apiConstructs.outline.getModel().getNameConverter().toPropertyName(decl.getName()) : customName;
		}

		private String getCustomPropertyName(final XSComponent component) {
			if(component.getAnnotation() != null && (component.getAnnotation().getAnnotation() instanceof  BindInfo)) {
				final BindInfo bindInfo = (BindInfo)component.getAnnotation().getAnnotation();
				final BIProperty biProperty = bindInfo.get(BIProperty.class);
				if(biProperty != null) {
					final String customPropertyName = biProperty.getPropertyName(false);
					return customPropertyName != null ? customPropertyName : null;
				}
			}
			return null;
		}
	};


	private static final Logger LOGGER = Logger.getLogger(GroupInterfaceGenerator.class.getName());
	private static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle.getBundle(GroupInterfaceGenerator.class.getName());

	private final boolean declareBuilderInterface;
	private final ApiConstructs apiConstructs;
	private final boolean throwsPropertyVetoException;
	private final boolean immutable;
	private final boolean cloneMethodThrows;
	private final boolean needsCloneMethod;
	private final boolean needsCopyMethod;
	private final String newBuilderMethodName;
	private final String newCopyBuilderMethodName;
	private final Map<String, List<InterfaceOutline>> interfacesByClass = new HashMap<>();
	private final EpisodeBuilder episodeBuilder;
	private final URL upstreamEpisode;

	private Map<QName, ReferencedInterfaceOutline> referencedInterfaces = null;

	public GroupInterfaceGenerator(final ApiConstructs apiConstructs, final URL upstreamEpisode, final EpisodeBuilder episodeBuilder, final boolean declareSetters, final boolean declareBuilderInterface, final String newBuilderMethodName, final String newCopyBuilderMethodName) {
		this.apiConstructs = apiConstructs;
		this.immutable = !declareSetters || this.apiConstructs.hasPlugin(ImmutablePlugin.class);

		final BoundPropertiesPlugin boundPropertiesPlugin = this.apiConstructs.findPlugin(BoundPropertiesPlugin.class);
		this.throwsPropertyVetoException = boundPropertiesPlugin != null && boundPropertiesPlugin.isConstrained() && boundPropertiesPlugin.isSetterThrows();
		final DeepClonePlugin deepClonePlugin = this.apiConstructs.findPlugin(DeepClonePlugin.class);
		final DeepCopyPlugin deepCopyPlugin = this.apiConstructs.findPlugin(DeepCopyPlugin.class);
		final FluentBuilderPlugin fluentBuilderPlugin = this.apiConstructs.findPlugin(FluentBuilderPlugin.class);
		this.declareBuilderInterface = declareBuilderInterface && fluentBuilderPlugin != null;
		this.needsCloneMethod = deepClonePlugin != null;
		this.cloneMethodThrows = this.needsCloneMethod && deepClonePlugin.isCloneThrows();
		this.needsCopyMethod = deepCopyPlugin != null;
		this.upstreamEpisode = upstreamEpisode;
		this.episodeBuilder = episodeBuilder;
		this.newBuilderMethodName = newBuilderMethodName;
		this.newCopyBuilderMethodName = newCopyBuilderMethodName;
	}

	private List<PropertyUse> findElementDecls(final XSModelGroupDecl modelGroup) {
		final List<PropertyUse> elementDecls = new ArrayList<>();
		for (final XSParticle child : modelGroup.getModelGroup()) {
			if (child.getTerm() instanceof XSElementDecl) {
				elementDecls.add(new PropertyUse(child.getTerm()));
			}
		}
		return elementDecls;
	}

	private List<PropertyUse> findAttributeDecls(final XSAttGroupDecl attGroupDecl) {
		final List<PropertyUse> attributeDecls = new ArrayList<>();
		for (final XSAttributeUse child : attGroupDecl.getDeclaredAttributeUses()) {
			attributeDecls.add(new PropertyUse(child));
		}
		return attributeDecls;
	}

	private List<PropertyUse> findChildDecls(final XSDeclaration groupDecl) {
		return (groupDecl instanceof XSAttGroupDecl) ? findAttributeDecls((XSAttGroupDecl) groupDecl) : findElementDecls((XSModelGroupDecl) groupDecl);
	}

	private static List<XSModelGroupDecl> findModelGroups(final Iterable<XSParticle> modelGroup) {
		final List<XSModelGroupDecl> elementDecls = new ArrayList<>();
		for (final XSParticle child : modelGroup) {
			if (child.getTerm() instanceof XSModelGroupDecl) {
				elementDecls.add((XSModelGroupDecl) child.getTerm());
			}
		}
		return elementDecls;
	}

	private static Collection<? extends XSDeclaration> findModelGroups(final XSComplexType complexType) {
		XSContentType contentType = complexType.getExplicitContent();
		if (contentType == null) {
			contentType = complexType.getContentType();
		}
		final XSParticle particle = contentType.asParticle();
		if (particle != null && !particle.isRepeated()) {
			final XSTerm term = particle.getTerm();
			if (term instanceof XSModelGroupDecl) {
				return Arrays.asList((XSModelGroupDecl) term);
			} else {
				final XSModelGroup modelGroup = term.asModelGroup();
				return modelGroup != null ? findModelGroups(modelGroup) : Collections.<XSModelGroupDecl>emptyList();
			}
		} else {
			return Collections.emptyList();
		}
	}

	private static Collection<? extends XSDeclaration> findAttributeGroups(final XSComplexType complexType) {
		return complexType.getAttGroups();
	}

	private static XSComplexType getTypeDefinition(final XSComponent xsTypeComponent) {
		if (xsTypeComponent instanceof XSAttContainer) {
			return (XSComplexType) xsTypeComponent;
		} else if (xsTypeComponent instanceof XSElementDecl) {
			return ((XSElementDecl) xsTypeComponent).getType().asComplexType();
		} else {
			return null;
		}
	}

	private static QName getQName(final XSDeclaration declaration) {
		return new QName(declaration.getTargetNamespace(), declaration.getName());
	}

	private static JMethod findGetter(final FieldOutline field) {
		final ClassOutline classOutline = field.parent();
		String propertyName = field.getPropertyInfo().getName(true);
		if ("Any".equals(propertyName)) {
			propertyName = "Content";
		}
		String getterName = "get" + propertyName;
		JMethod m = classOutline.implClass.getMethod(getterName, new JType[0]);
		if (m == null) {
			getterName = "is" + propertyName;
			m = classOutline.implClass.getMethod(getterName, new JType[0]);
		}
		return m;
	}

	private static JMethod findSetter(final FieldOutline field) {
		final ClassOutline classOutline = field.parent();
		String propertyName = field.getPropertyInfo().getName(true);
		if ("Any".equals(propertyName)) {
			propertyName = "Content";
		}
		final String setterName = "set" + propertyName;
		for (final JMethod method : classOutline.implClass.methods()) {
			if (method.name().equals(setterName) && method.listParams().length == 1) {
				return method;
			}
		}
		return null;
	}

	private static Map<QName, ReferencedInterfaceOutline> loadInterfaceEpisode(final ApiConstructs apiConstructs, final URL resource) {
		try {
			final Transformer transformer = GroupInterfacePlugin.TRANSFORMER_FACTORY.newTransformer(new StreamSource(GroupInterfaceGenerator.class.getResource("interface-bindings.xsl").toString()));
			final StreamSource episodeInput = new StreamSource(resource.toString());
			final DOMResult domResult = new DOMResult();
			transformer.transform(episodeInput, domResult);
			final Interfaces interfaces = JAXB.unmarshal(new DOMSource(domResult.getNode()), Interfaces.class);
			final Map<QName, ReferencedInterfaceOutline> interfaceMappings = new HashMap<>();
			for (final Interface iface : interfaces.getInterface()) {
				interfaceMappings.put(new QName(iface.getSchemaComponent().getNamespace(), iface.getSchemaComponent().getName()), new ReferencedInterfaceOutline(apiConstructs.codeModel.ref(iface.getName())));
			}
			return interfaceMappings;
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	private PackageOutline findPackageForNamespace(final String namespaceUri) {
		for (final PackageOutline packageOutline : this.apiConstructs.outline.getAllPackageContexts()) {
			if (namespaceUri.equals(packageOutline.getMostUsedNamespaceURI())) {
				return packageOutline;
			}
		}
		return null;
	}

	public List<InterfaceOutline> getGroupInterfacesForClass(final ClassOutline classOutline) {
		final List<InterfaceOutline> interfacesForClass = this.interfacesByClass.get(classOutline.implClass.fullName());
		return interfacesForClass == null ? Collections.<InterfaceOutline>emptyList() : interfacesForClass;
	}

	void putGroupInterfaceForClass(final ClassOutline classOutline, final InterfaceOutline groupInterface) {
		List<InterfaceOutline> interfacesForClass = this.interfacesByClass.get(classOutline.implClass.fullName());
		if (interfacesForClass == null) {
			interfacesForClass = new ArrayList<>();
			this.interfacesByClass.put(classOutline.implClass.fullName(), interfacesForClass);
		}
		interfacesForClass.add(groupInterface);
	}

	private Map<QName, InterfaceOutline> generateGroupInterfaces(final Iterator<? extends XSDeclaration> groupIterator) throws SAXException {
		final Map<QName, InterfaceOutline> groupInterfaces = new HashMap<>();

		// create interface for each group
		while (groupIterator.hasNext()) {
			final XSDeclaration modelGroup = groupIterator.next();
			if (!getReferencedInterfaces().containsKey(getQName(modelGroup))) {
				final InterfaceOutline interfaceOutline = createInterfaceDeclaration(modelGroup);
				if (interfaceOutline != null) {
					groupInterfaces.put(interfaceOutline.getName(), interfaceOutline);
					if (this.episodeBuilder != null) {
						this.episodeBuilder.addInterface(interfaceOutline.getSchemaComponent(), interfaceOutline.getImplClass());
					}
				}
			}
		}

		// Associate interfaces with superinterfaces
		for (final InterfaceOutline typeDef : groupInterfaces.values()) {
			final XSDeclaration classComponent = typeDef.getSchemaComponent();
			final Collection<? extends XSDeclaration> groupRefs = (classComponent instanceof XSAttGroupDecl) ? ((XSAttGroupDecl) classComponent).getAttGroups() : findModelGroups(((XSModelGroupDecl) classComponent).getModelGroup());
			for (final XSDeclaration groupRef : groupRefs) {
				TypeOutline superInterfaceOutline = groupInterfaces.get(getQName(groupRef));
				if (superInterfaceOutline == null) {
					superInterfaceOutline = getReferencedInterfaceOutline(getQName(groupRef));
				}
				associateSuperInterface(typeDef, superInterfaceOutline);
			}
		}

		return groupInterfaces;
	}

	public void generateGroupInterfaceModel() throws SAXException {
		final Map<QName, InterfaceOutline> modelGroupInterfaces = generateGroupInterfaces(this.apiConstructs.outline.getModel().schemaComponent.iterateModelGroupDecls());
		final Map<QName, InterfaceOutline> attGroupInterfaces = generateGroupInterfaces(this.apiConstructs.outline.getModel().schemaComponent.iterateAttGroupDecls());

		for (final ClassOutline classOutline : this.apiConstructs.outline.getClasses()) {
			final XSComponent xsTypeComponent = classOutline.target.getSchemaComponent();
			final XSComplexType classComponent = getTypeDefinition(xsTypeComponent);
			if (classComponent != null) {
				generateImplementsEntries(attGroupInterfaces, classOutline, findAttributeGroups(classComponent));
				generateImplementsEntries(modelGroupInterfaces, classOutline, findModelGroups(classComponent));
			}

		}

		for (final InterfaceOutline interfaceOutline : modelGroupInterfaces.values()) {
			removeDummyImplementation(interfaceOutline);
		}
		for (final InterfaceOutline interfaceOutline : attGroupInterfaces.values()) {
			removeDummyImplementation(interfaceOutline);
		}

		if (this.declareBuilderInterface) {
			final Map<String, BuilderOutline> builderOutlines = new HashMap<>();
			for (final InterfaceOutline interfaceOutline : modelGroupInterfaces.values()) {
				generateBuilderInterface(builderOutlines, interfaceOutline);
			}
			for (final InterfaceOutline interfaceOutline : attGroupInterfaces.values()) {
				generateBuilderInterface(builderOutlines, interfaceOutline);
			}

			for (final BuilderOutline builderOutline : builderOutlines.values()) {
				final BuilderGenerator builderGenerator = new BuilderGenerator(this.apiConstructs, builderOutlines, builderOutline, false, false, this.newBuilderMethodName, this.newCopyBuilderMethodName);
				builderGenerator.buildProperties();
			}
		}

	}

	private void generateBuilderInterface(final Map<String, BuilderOutline> builderOutlines, final InterfaceOutline interfaceOutline) throws SAXException {
		try {
			builderOutlines.put(interfaceOutline.getImplClass().fullName(), new BuilderOutline(interfaceOutline, interfaceOutline.getImplClass()._class(JMod.NONE, ApiConstructs.BUILDER_INTERFACE_NAME, ClassType.INTERFACE)));
		} catch (final JClassAlreadyExistsException e) {
			this.apiConstructs.errorHandler.error(new SAXParseException(MessageFormat.format(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("error.interface-exists"), interfaceOutline.getImplClass().fullName(), ApiConstructs.BUILDER_INTERFACE_NAME), interfaceOutline.getSchemaComponent().getLocator()));
		}
	}

	private void removeDummyImplementation(final InterfaceOutline interfaceOutline) {
		final ClassOutline classToRemove = interfaceOutline.getClassOutline();
		if (classToRemove != null) {
			final List<JMethod> methodsToRemove = new ArrayList<>();
			for (final JMethod method : classToRemove._package().objectFactory().methods()) {
				if (method.name().equals("create" + classToRemove.implClass.name())) {
					methodsToRemove.add(method);
				}
			}
			for (final JMethod method : methodsToRemove) {
				classToRemove._package().objectFactory().methods().remove(method);
			}
			this.apiConstructs.outline.getClasses().remove(classToRemove);
		}
	}

	private void generateImplementsEntries(final Map<QName, InterfaceOutline> groupInterfaces, final ClassOutline classOutline, final Iterable<? extends XSDeclaration> groupUses) throws SAXException {
		for (final XSDeclaration groupUse : groupUses) {
			final InterfaceOutline definedGroupType = groupInterfaces.get(getQName(groupUse));
			if (definedGroupType == null) {
				final TypeOutline referencedInterfaceOutline = getReferencedInterfaceOutline(getQName(groupUse));
				final String interfaceName;
				if (referencedInterfaceOutline == null) {
					final String pkg = this.apiConstructs.outline.getModel().getNameConverter().toPackageName(groupUse.getTargetNamespace());
					if (pkg == null) {
						this.apiConstructs.errorHandler.warning(new SAXParseException(MessageFormat.format(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("error.package-not-found"), groupUse.getTargetNamespace()), groupUse.getLocator()));
					}
					interfaceName = pkg + "." + this.apiConstructs.outline.getModel().getNameConverter().toClassName(groupUse.getName());
				} else {
					interfaceName = referencedInterfaceOutline.getImplClass().binaryName();
				}
				try {
					final Class<?> importedInterface = Class.forName(interfaceName);
					classOutline.implClass._implements(importedInterface);
				} catch (final ClassNotFoundException cnfe) {
					this.apiConstructs.errorHandler.warning(new SAXParseException(MessageFormat.format(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("error.interface-not-found"), groupUse.getName(), interfaceName), groupUse.getLocator()));
				}
			} else {
				classOutline.implClass._implements(definedGroupType.getImplClass());
				putGroupInterfaceForClass(classOutline, definedGroupType);
			}
		}
	}

	private void associateSuperInterface(final InterfaceOutline typeDefinition, final TypeOutline typeReference) {
		if (typeReference != null) {
			typeDefinition.setSuperInterface(typeReference);
			typeDefinition.getImplClass()._implements(typeReference.getImplClass());
		}
	}

	private InterfaceOutline createInterfaceDeclaration(final XSDeclaration groupDecl) throws SAXException {
		final PackageOutline packageOutline = findPackageForNamespace(groupDecl.getTargetNamespace());
		if (packageOutline == null) {
			this.apiConstructs.errorHandler.error(new SAXParseException(MessageFormat.format(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("error.package-not-found"), groupDecl.getTargetNamespace()), groupDecl.getLocator()));
			return null;
		}

		final JPackage container = packageOutline._package();
		final ClassOutline dummyImplementation = this.apiConstructs.classesBySchemaComponent.get(getQName(groupDecl));
		if (dummyImplementation == null) {
			this.apiConstructs.errorHandler.error(new SAXParseException(MessageFormat.format(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("error.no-implementation"), this.apiConstructs.outline.getModel().getNameConverter().toClassName(groupDecl.getName()), groupDecl.getTargetNamespace(), groupDecl.getName()), groupDecl.getLocator()));
			return null;
		}

		final String interfaceName = dummyImplementation.implClass.name();
		container.remove(dummyImplementation.implClass);
		final JDefinedClass groupInterface;
		try {
			groupInterface = container._interface(JMod.PUBLIC, interfaceName);
		} catch (final JClassAlreadyExistsException e) {
			this.apiConstructs.errorHandler.error(new SAXParseException(MessageFormat.format(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("error.interface-exists"), interfaceName, ""), groupDecl.getLocator()));
			return null;
		}
		final InterfaceOutline interfaceDecl = new InterfaceOutline(groupDecl, groupInterface, dummyImplementation);
			/*
			if (this.needsCopyMethod) {
				groupInterface._implements(Copyable.class);
			}

			if (this.needsCloneMethod) {
				groupInterface._implements(Cloneable.class);
				//groupInterface.method(JMod.NONE, Object.class, "clone");
			}*/

		// Generate Javadoc with schema fragment
		final StringWriter out = new StringWriter();
		out.write("<pre>\n");
		final SchemaWriter sw = new SchemaWriter(new JavadocEscapeWriter(out));
		groupDecl.visit(sw);
		out.write("</pre>");

		final JDocComment comment = groupInterface.javadoc();
		comment.append(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("comment.generated-from-xs-decl.header")).
				append("\n").
				append(MessageFormat.format(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("comment.generated-from-xs-decl.qname"),
						groupDecl.getTargetNamespace(),
						groupDecl.getName())).
				append("\n").
				append(MessageFormat.format(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("comment.generated-from-xs-decl.locator"),
						groupDecl.getLocator().getSystemId(),
						groupDecl.getLocator().getLineNumber(),
						groupDecl.getLocator().getColumnNumber()))
				.append("\n")
				.append(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("comment.generated-from-xs-decl.source"))
				.append("\n")
				.append(out.toString());


		for (final PropertyUse propertyUse : findChildDecls(groupDecl)) {
			final FieldOutline field = findField(dummyImplementation, propertyUse);
			if (field != null) {
				generateProperty(interfaceDecl, field);
			}
		}

		return interfaceDecl;
	}

	private FieldOutline findField(final ClassOutline implClass, final PropertyUse propertyUse) throws SAXException {
		if(!propertyUse.isFixed()) {
			for (final FieldOutline field : implClass.getDeclaredFields()) {
				if (field.getPropertyInfo().getName(true).equals(propertyUse.getName())) {
					return field;
				}
			}
			this.apiConstructs.errorHandler.error(new SAXParseException(MessageFormat.format(GroupInterfaceGenerator.RESOURCE_BUNDLE.getString("error.property-not-found"), propertyUse.declaration.toString(), propertyUse.getName(), implClass.implClass.fullName()), propertyUse.declaration.getLocator()));
		}
		return null;
	}

	private FieldOutline generateProperty(final InterfaceOutline groupInterface, final FieldOutline implementedField) {
		if (implementedField != null) {
			final JMethod implementedGetter = findGetter(implementedField);
			if (implementedGetter != null) {
				groupInterface.getImplClass().method(JMod.NONE, implementedGetter.type(), implementedGetter.name());
				if (!this.immutable) {
					final JMethod implementedSetter = findSetter(implementedField);
					if (implementedSetter != null) {
						final JMethod newSetter = groupInterface.getImplClass().method(JMod.NONE, implementedSetter.type(),
								implementedSetter.name());
						newSetter.param(implementedSetter.listParamTypes()[0], implementedSetter.listParams()[0].name());
						if (this.throwsPropertyVetoException) {
							newSetter._throws(PropertyVetoException.class);
						}
					}
				}
				groupInterface.addField(implementedField);
			}
		}
		return implementedField;
	}

	private ReferencedInterfaceOutline getReferencedInterfaceOutline(final QName schemaComponent) {
		return getReferencedInterfaces().get(schemaComponent);
	}

	Map<QName, ReferencedInterfaceOutline> getReferencedInterfaces() {
		if (this.referencedInterfaces == null) {
			if (this.upstreamEpisode != null) {
				this.referencedInterfaces = loadInterfaceEpisode(this.apiConstructs, this.upstreamEpisode);
			} else {
				this.referencedInterfaces = Collections.emptyMap();
			}
		}
		return this.referencedInterfaces;
	}

	private class PropertyUse {
		final XSComponent declaration;

		PropertyUse(final XSComponent declaration) {
			this.declaration = declaration;
		}

		String getName() {
			return this.declaration.apply(GroupInterfaceGenerator.this.nameFunc);
		}

		boolean isFixed() {
			return this.declaration.apply(GroupInterfaceGenerator.IS_FIXED_FUNC);
		}

	}


}

