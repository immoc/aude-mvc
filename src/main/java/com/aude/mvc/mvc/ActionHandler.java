package com.aude.mvc.mvc;

import com.aude.mvc.util.Logs;
import com.google.gson.Gson;
import com.aude.mvc.annotation.Parameter;
import com.aude.mvc.cache.MvcsManager;
import com.aude.mvc.constant.Constant;
import com.aude.mvc.error.ShiroAutcException;
import com.aude.mvc.error.WebErrorMessage;
import com.aude.mvc.ioc.Ioc;
import com.aude.mvc.util.ClassTool;
import com.aude.mvc.util.ParameterConverter;
import org.apache.log4j.Logger;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: audestick@gmail.com
 * Date: 2016/5/9 0009
 * To change this template use File | Settings | File Templates.
 */
public class ActionHandler {

    private static final Logger logger = Logs.get();

    /**
     * 执行action
     *
     * @param servletPath
     * @param methodType
     * @param request
     * @param response
     * @return
     */
    public static ActionResult invokeAction(String servletPath, String methodType, HttpServletRequest request, HttpServletResponse response) {
        WebErrorMessage webErrorMessage = new WebErrorMessage();
        webErrorMessage.setCode(200);
        ActionResult actionResult = new ActionResult();
        try {
            ActionMethod actionMethod = MvcsManager.getUrlCache(servletPath, methodType);
            if (actionMethod != null) {
                actionResult.setResultType(actionMethod.getOK());
                Class<?> actionClass = actionMethod.getActionClass();
                Method handlerMethod = actionMethod.getActionMethod();
                handlerMethod.setAccessible(true);
                String iocBeanName = ClassTool.getIocBeanName(actionClass);
                Object beanInstance = Ioc.getBean(iocBeanName);
                if (beanInstance == null) {
                    beanInstance = ClassTool.getInstance(actionClass);
                }
                /**
                 * 自动注入参数
                 */
                inject(actionClass.getDeclaredFields(), beanInstance, request, response);
                inject(actionClass.getSuperclass().getDeclaredFields(), beanInstance, request, response);


                Class<?>[] actionParamTypes = handlerMethod.getParameterTypes();
                List<Object> actionParamList = new ArrayList<>();
                Annotation[][] annotations = handlerMethod.getParameterAnnotations();
                Map<String, ?> requestParameterMap = Mvcs.getReqMap();
                for (int i = 0; i < annotations.length; i++) {
                    Annotation[] annotation = annotations[i];
                    Class anClass = actionParamTypes[i];
                    if (annotation.length > 0) {
                        for (Annotation anno : annotation) {
                            if (anno instanceof Parameter) {
                                String webParamKeyName = ((Parameter) anno).value();
                                if (webParamKeyName.endsWith(">>")) {
                                    webParamKeyName = webParamKeyName.replace(">>", "");
                                    actionParamList.add(ParameterConverter.bulid(anClass, webParamKeyName, requestParameterMap));
                                } else {
                                    Object ParamValuesObject = requestParameterMap.get(webParamKeyName);
                                    Object val = ClassTool.ParamCast(anClass, ParamValuesObject);
                                    actionParamList.add(val);
                                }
                            }
                        }
                    } else if (anClass.equals(HttpServletRequest.class)) {
                        actionParamList.add(request);
                    } else if (anClass.equals(HttpServletResponse.class)) {
                        actionParamList.add(response);
                    } else if (anClass.equals(HttpSession.class)) {
                        actionParamList.add(request.getSession());
                    } else if (anClass.equals(ServletContext.class)) {
                        actionParamList.add(request.getServletContext());
                    } else {
                        webErrorMessage.setCode(500);
                        webErrorMessage.setMessage("Action的参数,除HttpServletRequest,HttpServletResponse外必须使用@" + Parameter.class + "注解");
                        logger.error(webErrorMessage.getMessage());
                    }
                }
                try {
                    Object object = handlerMethod.invoke(beanInstance, actionParamList.toArray());
                    actionResult.setResultData(object);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            } else if (servletPath.endsWith(Constant.PAGE_SUFFIX)) {
                /**
                 * 在没有找到注解的情况下，并且访问的是jsp文件
                 */
                webErrorMessage.setJsp(true);
            } else {
                webErrorMessage.setCode(404);
            }
        } catch (Throwable e) {
            webErrorMessage.setAjax(Mvcs.isAjax());
            Throwable te = e == null ? e.getCause() : e;
            String message = te.getMessage() == null ? te.toString() : te.getMessage();
            Map map = new HashMap();
            map.put("ok", false);
            map.put("msg", message);
            if (te instanceof ShiroAutcException) {
                webErrorMessage.setRedirectUrl(((ShiroAutcException) te).getRedirectUrl());
                map.put("redirecUrl", webErrorMessage.getRedirectUrl());
                webErrorMessage.setMessage(new Gson().toJson(map));
            } else if (te != null) {
                if (Mvcs.isAjax()) {
                    webErrorMessage.setMessage(new Gson().toJson(map));
                } else {
                    webErrorMessage.setMessage(te.getMessage());
                }
            } else {
                if (Mvcs.isAjax()) {
                    webErrorMessage.setMessage(new Gson().toJson(map));
                } else {
                    webErrorMessage.setMessage(e.getMessage());
                }
            }
            webErrorMessage.setCode(500);
            te.printStackTrace();
            logger.trace(te);
            logger.error(te);
        }
        if (webErrorMessage.getCode() == 404) {
            webErrorMessage.setMessage("  [" + methodType + "] Not Found URI=" + servletPath);
            logger.debug(webErrorMessage.getMessage());
        }
        actionResult.setWebErrorMessage(webErrorMessage);
        return actionResult;
    }

    /**
     * 自动注入参数
     *
     * @param fields
     * @param beanInstance
     * @param request
     * @param response
     * @throws IllegalAccessException
     */
    private static void inject(Field[] fields, Object beanInstance, HttpServletRequest request, HttpServletResponse response) throws IllegalAccessException {
        for (Field field : fields) {
            field.setAccessible(true);
            if (field.getGenericType().equals(HttpServletRequest.class)) {
                field.set(beanInstance, request);
            } else if (field.getGenericType().equals(HttpServletResponse.class)) {
                field.set(beanInstance, response);
            } else if (field.getGenericType().equals(HttpSession.class)) {
                field.set(beanInstance, request.getSession());
            } else if (field.getGenericType().equals(ServletContext.class)) {
                field.set(beanInstance, request.getServletContext());
            }
        }
    }
}
