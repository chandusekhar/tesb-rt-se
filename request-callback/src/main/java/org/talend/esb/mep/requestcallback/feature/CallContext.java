package org.talend.esb.mep.requestcallback.feature;

import java.io.File;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Dispatch;
import javax.xml.ws.Endpoint;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceContext;
import javax.xml.ws.handler.MessageContext;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.feature.Feature;
import org.apache.cxf.feature.LoggingFeature;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.DispatchImpl;
import org.apache.cxf.jaxws.EndpointImpl;
import org.apache.cxf.jaxws.JaxWsClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.apache.cxf.service.factory.AbstractServiceConfiguration;
import org.apache.cxf.service.factory.DefaultServiceConfiguration;
import org.talend.esb.mep.requestcallback.impl.wsdl.CallbackDefaultServiceConfiguration;

public class CallContext implements Serializable {

	private static final String NULL_MEANS_ONEWAY = "jaxws.provider.interpretNullAsOneway";
	private static final long serialVersionUID = -5024912330689208965L;
	
	private QName portTypeName;
	private QName serviceName;
	private QName operationName;
	private String requestId;
	private String callId;
	private String callbackId;
	private String replyToAddress;
	private String bindingId;
	private URL wsdlLocationURL;
	private Map<String, String> userData;
	private transient CallbackInfo callbackInfo = null;
	private static boolean logging = false;

	public QName getPortTypeName() {
		return portTypeName;
	}

	public void setPortTypeName(QName portTypeName) {
		this.portTypeName = portTypeName;
		this.callbackInfo = null;
	}

	public QName getServiceName() {
		return serviceName;
	}

	public void setServiceName(QName serviceName) {
		this.serviceName = serviceName;
		this.callbackInfo = null;
	}

	public QName getOperationName() {
		return operationName;
	}

	public void setOperationName(QName operationName) {
		this.operationName = operationName;
	}

	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}

	public String getCallId() {
		return callId;
	}

	public void setCallId(String callId) {
		this.callId = callId;
	}

	public String getCallbackId() {
		return callbackId;
	}

	public void setCallbackId(String callbackId) {
		this.callbackId = callbackId;
	}

	public String getReplyToAddress() {
		return replyToAddress;
	}

	public void setReplyToAddress(String replyToAddress) {
		this.replyToAddress = replyToAddress;
	}

	public String getBindingId() {
		return bindingId;
	}

	public void setBindingId(String bindingId) {
		this.bindingId = bindingId;
	}

	public String getWsdlLocation() {
		return wsdlLocationURL == null ? null : wsdlLocationURL.toExternalForm();
	}

	public void setWsdlLocation(String wsdlLocation) throws MalformedURLException {
		this.wsdlLocationURL = wsdlLocation == null ? null : new URL(wsdlLocation);
		this.callbackInfo = null;
	}

	public void setWsdlLocation(File wsdlLocation) throws MalformedURLException {
		this.wsdlLocationURL = wsdlLocation == null ? null : wsdlLocation.toURI().toURL();
		this.callbackInfo = null;
	}

	public void setWsdlLocation(URL wsdlLocation) {
		setWsdlLocationURL(wsdlLocation);
	}

	public URL getWsdlLocationURL(URL wsdlLocationURL) {
		return wsdlLocationURL;
	}

	public void setWsdlLocationURL(URL wsdlLocationURL) {
		this.wsdlLocationURL = wsdlLocationURL;
		this.callbackInfo = null;
	}

	public CallbackInfo getCallbackInfo() {
		if (callbackInfo == null && wsdlLocationURL != null) {
			callbackInfo = new CallbackInfo(wsdlLocationURL);
		}
		return callbackInfo;
	}

	public Map<String, String> getUserData() {
		if (userData == null) {
			userData = new HashMap<String, String>();
		}
		return userData;
	}

	public boolean hasUserData() {
		return userData != null && !userData.isEmpty();
	}

	public static boolean isLogging() {
		return logging;
	}

	public static void setLogging(boolean logging) {
		CallContext.logging = logging;
	}

	public <T> T createCallbackProxy(Class<T> proxyInterface) {
        JaxWsProxyFactoryBean callback = new JaxWsProxyFactoryBean();
        callback.setServiceName(serviceName);
        callback.setEndpointName(new QName(serviceName.getNamespaceURI(), serviceName.getLocalPart() + "Port"));
        callback.setAddress(replyToAddress);
        callback.setServiceClass(proxyInterface);
        callback.getFeatures().add(new RequestCallbackFeature());
        if (logging) {
        	callback.getFeatures().add(new LoggingFeature());
        }
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(RequestCallbackFeature.CALLCONTEXT_PROPERTY_NAME, this);
        callback.setProperties(properties);
        return callback.create(proxyInterface);
	}

	public <T> void initCallbackProxy(T proxy) {
		setupCallbackProxy(proxy);
	}

	public <T extends Source> Dispatch<T> createCallbackDispatch(
			Class<T> sourceClass, Service.Mode mode, QName operation) {
		QName callbackPortTypeName = new QName(
				portTypeName.getNamespaceURI(), portTypeName.getLocalPart() + "Consumer");
		QName callbackServiceName = new QName(
				callbackPortTypeName.getNamespaceURI(), callbackPortTypeName.getLocalPart() + "Service");
		QName callbackPortName = new QName(
				callbackPortTypeName.getNamespaceURI(), callbackPortTypeName.getLocalPart() + "Port");
        Service service = Service.create(callbackServiceName);
        service.addPort(callbackPortName, bindingId, replyToAddress);
        Dispatch<T> dispatch = service.createDispatch(
        		callbackPortName, sourceClass, mode);
        setupDispatch(dispatch);
        Map<String, Object> requestContext = dispatch.getRequestContext();
        requestContext.put(RequestCallbackFeature.CALLCONTEXT_PROPERTY_NAME, this);
        // The current request context is still not thread local, but subsequent
        // calls to dispatch.getRequestContext() return a thread local one.
        requestContext.put(JaxWsClientProxy.THREAD_LOCAL_REQUEST_CONTEXT, Boolean.TRUE);
        if (operation != null) {
            requestContext.put(MessageContext.WSDL_OPERATION, operation);
            requestContext.put(BindingProvider.SOAPACTION_USE_PROPERTY, Boolean.TRUE);
            requestContext.put(BindingProvider.SOAPACTION_URI_PROPERTY, operation.getLocalPart());
        }
		return dispatch;
	}

	public <T extends Source> Dispatch<T> createCallbackDispatch(Class<T> sourceClass, QName operation) {
		return createCallbackDispatch(sourceClass, Service.Mode.PAYLOAD, operation);
	}

	public <T extends Source> Dispatch<T> createCallbackDispatch(Class<T> sourceClass) {
		return createCallbackDispatch(sourceClass, Service.Mode.PAYLOAD, null);
	}

	public Dispatch<StreamSource> createCallbackDispatch(QName operation) {
		return createCallbackDispatch(StreamSource.class, Service.Mode.PAYLOAD, operation);
	}

	public Dispatch<StreamSource> createCallbackDispatch() {
		return createCallbackDispatch(StreamSource.class, Service.Mode.PAYLOAD, null);
	}

	public static CallContext getCallContext(WebServiceContext wsContext) {
		return getCallContext(wsContext.getMessageContext());
	}

	public static CallContext getCallContext(Map<?, ?> contextHolder) {
		try {
			return (CallContext) contextHolder.get(RequestCallbackFeature.CALLCONTEXT_PROPERTY_NAME);
		} catch (ClassCastException e) {
			return null;
		}
	}

	public static Endpoint createCallbackEndpoint(Object implementor, String wsdlLocation) {
		return createCallbackEndpoint(implementor, new CallbackInfo(wsdlLocation));
	}

	public static Endpoint createCallbackEndpoint(Object implementor, URL wsdlLocation) {
		return createCallbackEndpoint(implementor, new CallbackInfo(wsdlLocation));
	}

	public static void setCallbackEndpoint(Dispatch<?> dispatch, Object callbackEndpoint) {
		dispatch.getRequestContext().put(RequestCallbackFeature.CALLBACK_ENDPOINT_PROPERTY_NAME, callbackEndpoint);
	}

	public static void setCallbackEndpoint(Map<String, Object> context, Object callbackEndpoint) {
		context.put(RequestCallbackFeature.CALLBACK_ENDPOINT_PROPERTY_NAME, callbackEndpoint);
	}

	public static void setupEndpoint(Endpoint endpoint) {
		if (!(endpoint instanceof EndpointImpl)) {
			throw new IllegalArgumentException("Only CXF JAX-WS endpoints supported. ");
		}
		EndpointImpl ep = (EndpointImpl) endpoint;
        List<Feature> features = new ArrayList<Feature>();
        features.add(new RequestCallbackFeature());
        if (logging) {
        	features.add(new LoggingFeature());
        }
        ep.setFeatures(features);
        ep.getProperties().put(NULL_MEANS_ONEWAY, Boolean.TRUE);
	}

	public static void setupDispatch(Dispatch<?> dispatch) {
		if (!(dispatch instanceof DispatchImpl)) {
			throw new IllegalArgumentException("Only CXF JAX-WS Dispatch supported. ");
		}
		DispatchImpl<?> dsp = (DispatchImpl<?>) dispatch;
        Client dispatchClient = dsp.getClient();
        (new RequestCallbackFeature()).initialize(dispatchClient, dispatchClient.getBus());
        if (logging) {
	        (new LoggingFeature()).initialize(dispatchClient, dispatchClient.getBus());
        }
	}

	public static void setupDispatch(Dispatch<?> dispatch, Object callbackEndpoint) {
		setupDispatch(dispatch);
		setCallbackEndpoint(dispatch, callbackEndpoint);
	}

	public static void setupServerFactory(JaxWsServerFactoryBean serverFactory) {
		List<Feature> features = serverFactory.getFeatures();
        features.add(new RequestCallbackFeature());
        if (logging) {
	        features.add(new LoggingFeature());
        }
        serverFactory.getProperties(true).put(NULL_MEANS_ONEWAY, Boolean.TRUE);
	}

	public <T> void setupCallbackProxy(T proxy) {
		final Client client = ClientProxy.getClient(proxy);
        (new RequestCallbackFeature()).initialize(client, client.getBus());
        if (logging) {
	        (new LoggingFeature()).initialize(client, client.getBus());
        }
        final BindingProvider bp = (BindingProvider) proxy;
        bp.getRequestContext().put(
        		JaxWsClientProxy.THREAD_LOCAL_REQUEST_CONTEXT, Boolean.TRUE);
        // Now re-get the request context as thread local.
        final Map<String, Object> rctx = bp.getRequestContext();
        rctx.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, replyToAddress);
        rctx.put(RequestCallbackFeature.CALLCONTEXT_PROPERTY_NAME, this);
	}

	public static CallbackInfo createCallbackInfo(String wsdlLocation) {
		return new CallbackInfo(wsdlLocation);
	}

	public static CallbackInfo createCallbackInfo(URL wsdlLocationURL) {
		return new CallbackInfo(wsdlLocationURL);
	}

	public static void enforceOperation(QName operationName, Dispatch<?> dispatch) {
		enforceOperation(operationName, dispatch.getRequestContext());
	}

	public static void enforceOperation(QName operationName, Map<String, Object> requestContext) {
        requestContext.put(MessageContext.WSDL_OPERATION, operationName);
        requestContext.put(BindingProvider.SOAPACTION_USE_PROPERTY, Boolean.TRUE);
        requestContext.put(BindingProvider.SOAPACTION_URI_PROPERTY, operationName.getLocalPart());
	}

	public static Configuration resolveConfiguration(QName serviceName) {
		return ConfigurationInitializer.resolveConfiguration(serviceName);
	}

	private static Endpoint createCallbackEndpoint(Object implementor, CallbackInfo cbInfo) {
		Bus bus = BusFactory.getThreadDefaultBus();
		JaxWsServerFactoryBean serverFactory = new JaxWsServerFactoryBean();
        List<Feature> features = new ArrayList<Feature>();
        features.add(new RequestCallbackFeature());
        if (logging) {
        	features.add(new LoggingFeature());
        }
		serverFactory.setFeatures(features);
		QName cbInterfaceName = cbInfo == null ? null : cbInfo.getCallbackPortTypeName();
		String wsdlLocation = cbInfo == null ? null : cbInfo.getWsdlLocation();
		boolean useWsdlLocation = wsdlLocation != null && cbInfo.getCallbackServiceName() != null &&
				cbInfo.getCallbackPortName() != null;
		if (cbInterfaceName != null) {
			QName cbServiceName = cbInfo.getCallbackServiceName() == null
					? new QName(cbInterfaceName.getNamespaceURI(), cbInterfaceName.getLocalPart() + "Service")
					: cbInfo.getCallbackServiceName();
			QName cbEndpointName = cbInfo.getCallbackServiceName() == null
					? new QName(cbInterfaceName.getNamespaceURI(), cbInterfaceName.getLocalPart() + "ServicePort")
					: new QName(cbServiceName.getNamespaceURI(), cbInfo.getCallbackPortName() == null
							? cbServiceName.getLocalPart() + "Port"
							: cbInfo.getCallbackPortName());
			serverFactory.setServiceName(cbServiceName);
			serverFactory.setEndpointName(cbEndpointName);
			List<AbstractServiceConfiguration> svcConfigs = serverFactory.getServiceFactory().getServiceConfigurations();
			for (ListIterator<AbstractServiceConfiguration> it = svcConfigs.listIterator(); it.hasNext(); ) {
				AbstractServiceConfiguration cfg = it.next();
				if (cfg instanceof DefaultServiceConfiguration) {
					AbstractServiceConfiguration ncfg = new CallbackDefaultServiceConfiguration(cbInfo);
					it.set(ncfg);
				}
			}
			if (useWsdlLocation) {
				serverFactory.setWsdlLocation(wsdlLocation);
			}
		}
		EndpointImpl endpoint = new EndpointImpl(bus, implementor, serverFactory);
		endpoint.setFeatures(features);
        endpoint.getProperties().put(NULL_MEANS_ONEWAY, Boolean.TRUE);
        if (cbInterfaceName != null) {
        	endpoint.setEndpointName(serverFactory.getEndpointName());
        	endpoint.setServiceName(serverFactory.getServiceName());
        	if (useWsdlLocation) {
        		endpoint.setWsdlLocation(wsdlLocation);
        	}
        }
		return endpoint;
	}
}
