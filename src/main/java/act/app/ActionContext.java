package act.app;

import act.Act;
import act.Destroyable;
import act.data.MapUtil;
import act.data.RequestBodyParser;
import act.event.ActEvent;
import act.handler.RequestHandler;
import act.route.Router;
import act.util.ActContext;
import org.osgl._;
import org.osgl.concurrent.ContextLocal;
import org.osgl.http.H;
import org.osgl.http.H.Cookie;
import org.osgl.mvc.util.ParamValueProvider;
import org.osgl.storage.ISObject;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;

import javax.validation.ConstraintViolation;
import java.util.*;

/**
 * {@code AppContext} encapsulate contextual properties needed by
 * an application session
 */
public class ActionContext extends ActContext.ActContextBase<ActionContext> implements ActContext<ActionContext>, ParamValueProvider, Destroyable {

    public static final String ATTR_HANDLER = "__act_handler__";

    private H.Request request;
    private H.Response response;
    private H.Session session;
    private H.Flash flash;
    private Set<Map.Entry<String, String[]>> requestParamCache;
    private Map<String, String> extraParams;
    private volatile Map<String, String[]> bodyParams;
    private Map<String, String[]> allParams;
    private String actionPath; // e.g. com.mycorp.myapp.controller.AbcController.foo
    private Map<String, Object> attributes;
    private State state;
    private Map<String, Object> controllerInstances;
    private List<ISObject> uploads;
    private Set<ConstraintViolation> violations;
    private Router router;
    private RequestHandler handler;

    private ActionContext(App app, H.Request request, H.Response response) {
        super(app);
        E.NPE(app, request, response);
        this.request = request;
        this.response = response;
        this._init();
        this.state = State.CREATED;
        this.saveLocal();
    }

    public State state() {
        return state;
    }

    public boolean isSessionDissolved() {
        return state == State.SESSION_DISSOLVED;
    }

    public boolean isSessionResolved() {
        return state == State.SESSION_RESOLVED;
    }

    public H.Request req() {
        return request;
    }

    public H.Response resp() {
        return response;
    }

    public H.Cookie cookie(String name) {
        return req().cookie(name);
    }

    public H.Session session() {
        return session;
    }

    public H.Flash flash() {
        return flash;
    }

    public Router router() {
        return router;
    }

    public ActionContext router(Router router) {
        E.NPE(router);
        this.router = router;
        return this;
    }

    // !!!IMPORTANT! the following methods needs to be kept to allow enhancer work correctly

    @Override
    public <T> T renderArg(String name) {
        return super.renderArg(name);
    }

    @Override
    public ActionContext renderArg(String name, Object val) {
        return super.renderArg(name, val);
    }

    @Override
    public Map<String, Object> renderArgs() {
        return super.renderArgs();
    }

    @Override
    public ActionContext templatePath(String templatePath) {
        return super.templatePath(templatePath);
    }

    public RequestHandler handler() {
        return handler;
    }

    public ActionContext handler(RequestHandler handler) {
        E.NPE(handler);
        this.handler = handler;
        return this;
    }

    public H.Format accept() {
        return req().accept();
    }

    public ActionContext accept(H.Format fmt) {
        req().accept(fmt);
        return this;
    }

    public Locale locale() {
        return config().localeResolver().resolve(this);
    }

    public boolean isJSON() {
        return accept() == H.Format.json;
    }

    public boolean isAjax() {
        return req().isAjax();
    }

    public ActionContext param(String name, String value) {
        extraParams.put(name, value);
        return this;
    }

    @Override
    public String paramVal(String name) {
        String val = extraParams.get(name);
        if (null != val) {
            return val;
        }
        val = request.paramVal(name);
        if (null == val) {
            String[] sa = bodyParams().get(name);
            if (null != sa && sa.length > 0) {
                val = sa[0];
            }
        }
        return val;
    }

    public String[] paramVals(String name) {
        String val = extraParams.get(name);
        if (null != val) {
            return new String[]{val};
        }
        String[] sa = request.paramVals(name);
        if (null == sa) {
            sa = bodyParams().get(name);
        }
        return sa;
    }

    private Map<String, String[]> bodyParams() {
        if (null == bodyParams) {
            synchronized (this) {
                if (null == bodyParams) {
                    Map<String, String[]> map = C.newMap();
                    H.Method method = request.method();
                    if (H.Method.POST == method || H.Method.PUT == method) {
                        RequestBodyParser parser = RequestBodyParser.get(request);
                        map = parser.parse(this);
                    }
                    bodyParams = map;
                }
            }
        }
        return bodyParams;
    }

    public Map<String, String[]> allParams() {
        return allParams;
    }

    public List<ISObject> uploads() {
        return C.list(uploads);
    }

    public ActionContext addUpload(ISObject sobj) {
        uploads.add(sobj);
        return this;
    }

    /**
     * Called by bytecode enhancer to set the name list of the render arguments that is update
     * by the enhancer
     * @param names the render argument names separated by ","
     * @return this AppContext
     */
    public ActionContext __appRenderArgNames(String names) {
        return renderArg("__arg_names__", C.listOf(names.split(",")));
    }

    public List<String> __appRenderArgNames() {
        return renderArg("__arg_names__");
    }

    public ActionContext __controllerInstance(String className, Object instance) {
        if (null == controllerInstances) {
            controllerInstances = C.newMap();
        }
        controllerInstances.put(className, instance);
        return this;
    }

    public Object __controllerInstance(String className) {
        return null == controllerInstances ? null : controllerInstances.get(className);
    }

    /**
     * Associate a user attribute to the context. Could be used by third party
     * libraries or user application
     *
     * @param name the className used to reference the attribute
     * @param attr the attribute object
     * @return this context
     */
    public ActionContext attribute(String name, Object attr) {
        attributes.put(name, attr);
        return this;
    }

    public <T> T attribute(String name) {
        return _.cast(attributes.get(name));
    }

    public <T> T newInstance(Class<? extends T> clazz) {
        if (clazz == ActionContext.class) return _.cast(this);
        return app().newInstance(clazz, this);
    }

    public ActionContext addViolations(Set<ConstraintViolation<?>> violations) {
        this.violations.addAll(violations);
        return this;
    }

    public ActionContext addViolation(ConstraintViolation<?> violation) {
        this.violations.add(violation);
        return this;
    }

    public boolean hasViolation() {
        return !violations.isEmpty();
    }

    public Set<ConstraintViolation> violations() {
        return C.set(this.violations);
    }

    public StringBuilder buildViolationMessage(StringBuilder builder) {
        return buildViolationMessage(builder, "\n");
    }

    public StringBuilder buildViolationMessage(StringBuilder builder, String separator) {
        if (violations.isEmpty()) return builder;
        for (ConstraintViolation violation : violations) {
            builder.append(violation.getMessage()).append(separator);
        }
        int n = builder.length();
        builder.delete(n - separator.length(), n);
        return builder;
    }

    public String violationMessage(String separator) {
        return buildViolationMessage(S.builder(), separator).toString();
    }

    public String violationMessage() {
        return violationMessage("\n");
    }

    public ActionContext flashViolationMessage() {
        return flashViolationMessage("\n");
    }

    public ActionContext flashViolationMessage(String separator) {
        if (violations.isEmpty()) return this;
        flash().error(violationMessage(separator));
        return this;
    }

    public String actionPath() {
        return actionPath;
    }

    public ActionContext actionPath(String path) {
        actionPath = path;
        return this;
    }

    /**
     * If {@link #templatePath(String) template path has been set before} then return
     * the template path. Otherwise returns the {@link #actionPath()}
     * @return either template path or action path if template path not set before
     */
    public String templatePath() {
        String path = super.templatePath();
        if (S.notBlank(path)) {
            return path;
        } else {
            return actionPath().replace('.', '/');
        }
    }

    /**
     * Initialize params/renderArgs/attributes and then
     * resolve session and flash from cookies
     */
    public void resolve() {
        E.illegalStateIf(state != State.CREATED);
        resolveSession();
        resolveFlash();
        state = State.SESSION_RESOLVED;
        Act.sessionManager().fireSessionResolved(this);
        app().eventBus().emit(new SessionResolvedEvent(this));
    }

    /**
     * Dissolve session and flash into cookies.
     * <p><b>Note</b> this method must be called
     * before any content has been committed to
     * response output stream/writer</p>
     */
    public void dissolve() {
        if (state == State.SESSION_DISSOLVED) {
            return;
        }
        app().eventBus().emit(new SessionWillDissolveEvent(this));
        try {
            dissolveFlash();
            dissolveSession();
            state = State.SESSION_DISSOLVED;
        } finally {
            app().eventBus().emit(new SessionDissolvedEvent(this));
        }
    }

    /**
     * Clear all internal data store/cache and then
     * remove this context from thread local
     */
    @Override
    protected void releaseResources() {
        super.releaseResources();
        if (this.state != State.DESTROYED) {
            this.allParams = null;
            this.extraParams = null;
            this.requestParamCache = null;
            this.attributes.clear();
            this.router = null;
            this.handler = null;
            // xio impl might need this this.request = null;
            // xio impl might need this this.response = null;
            this.flash = null;
            this.session = null;
            this.controllerInstances = null;
            this.violations.clear();
            this.clearLocal();
            this.uploads.clear();
            for (Object o : this.attributes.values()) {
                if (o instanceof Destroyable) {
                    ((Destroyable) o).destroy();
                }
            }
            this.attributes.clear();
        }
        this.state = State.DESTROYED;
    }

    public void saveLocal() {
        _local.set(this);
    }

    public static void clearLocal() {
        _local.remove();
    }

    private Set<Map.Entry<String, String[]>> requestParamCache() {
        if (null != requestParamCache) {
            return requestParamCache;
        }
        requestParamCache = new HashSet<Map.Entry<String, String[]>>();
        Map<String, String[]> map = C.newMap();
        // url queries
        Iterator<String> paramNames = request.paramNames().iterator();
        while (paramNames.hasNext()) {
            final String key = paramNames.next();
            final String[] val = request.paramVals(key);
            MapUtil.mergeValueInMap(map, key, val);
        }
        // post bodies
        Map<String, String[]> map2 = bodyParams();
        for (String key : map2.keySet()) {
            String[] val = map2.get(key);
            if (null != val) {
                MapUtil.mergeValueInMap(map, key, val);
            }
        }
        requestParamCache.addAll(map.entrySet());
        return requestParamCache;
    }

    private void _init() {
        uploads = C.newList();
        extraParams = C.newMap();
        violations = C.newSet();
        attributes = C.newMap();
        final Set<Map.Entry<String, String[]>> paramEntrySet = new AbstractSet<Map.Entry<String, String[]>>() {
            @Override
            public Iterator<Map.Entry<String, String[]>> iterator() {
                final Iterator<Map.Entry<String, String[]>> extraItr = new Iterator<Map.Entry<String, String[]>>() {
                    Iterator<Map.Entry<String, String>> parent = extraParams.entrySet().iterator();

                    @Override
                    public boolean hasNext() {
                        return parent.hasNext();
                    }

                    @Override
                    public Map.Entry<String, String[]> next() {
                        final Map.Entry<String, String> parentEntry = parent.next();
                        return new Map.Entry<String, String[]>() {
                            @Override
                            public String getKey() {
                                return parentEntry.getKey();
                            }

                            @Override
                            public String[] getValue() {
                                return new String[]{parentEntry.getValue()};
                            }

                            @Override
                            public String[] setValue(String[] value) {
                                throw E.unsupport();
                            }
                        };
                    }

                    @Override
                    public void remove() {
                        throw E.unsupport();
                    }
                };
                final Iterator<Map.Entry<String, String[]>> reqParamItr = requestParamCache().iterator();
                return new Iterator<Map.Entry<String, String[]>>() {
                    @Override
                    public boolean hasNext() {
                        return extraItr.hasNext() || reqParamItr.hasNext();
                    }

                    @Override
                    public Map.Entry<String, String[]> next() {
                        if (extraItr.hasNext()) return extraItr.next();
                        return reqParamItr.next();
                    }

                    @Override
                    public void remove() {
                        throw E.unsupport();
                    }
                };
            }

            @Override
            public int size() {
                int size = extraParams.size();
                if (null != request) {
                    size += requestParamCache().size();
                }
                return size;
            }
        };

        allParams = new AbstractMap<String, String[]>() {
            @Override
            public Set<Entry<String, String[]>> entrySet() {
                return paramEntrySet;
            }
        };
    }

    private void resolveSession() {
        this.session = Act.sessionManager().resolveSession(this);
    }

    private void resolveFlash() {
        this.flash = Act.sessionManager().resolveFlash(this);
    }

    private void dissolveSession() {
        Cookie c = Act.sessionManager().dissolveSession(this);
        if (null != c) {
            config().sessionMapper().serializeSession(c, this);
        }
    }

    private void dissolveFlash() {
        Cookie c = Act.sessionManager().dissolveFlash(this);
        if (null != c) {
            config().sessionMapper().serializeFlash(c, this);
        }
    }

    private static ContextLocal<ActionContext> _local = _.contextLocal();

    public static final String METHOD_GET_CURRENT = "current";

    public static ActionContext current() {
        return _local.get();
    }

    public static void clearCurrent() {
        _local.remove();
    }

    /**
     * Create an new {@code AppContext} and return the new instance
     */
    public static ActionContext create(App app, H.Request request, H.Response resp) {
        return new ActionContext(app, request, resp);
    }

    public enum State {
        CREATED,
        SESSION_RESOLVED,
        SESSION_DISSOLVED,
        DESTROYED
    }

    private static class ActionContextEvent extends ActEvent<ActionContext> {
        public ActionContextEvent(ActionContext source) {
            super(source);
        }
    }

    public static class SessionResolvedEvent extends ActionContextEvent {
        public SessionResolvedEvent(ActionContext source) {
            super(source);
        }
    }

    public static class SessionWillDissolveEvent extends ActionContextEvent {
        public SessionWillDissolveEvent(ActionContext source) {
            super(source);
        }
    }

    public static class SessionDissolvedEvent extends ActionContextEvent {
        public SessionDissolvedEvent(ActionContext source) {
            super(source);
        }
    }
}