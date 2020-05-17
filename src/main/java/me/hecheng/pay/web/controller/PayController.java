package me.hecheng.pay.web.controller;

import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import lombok.extern.slf4j.Slf4j;
import me.hecheng.pay.bean.ALiYunConstat;
import me.hecheng.pay.component.DistributedLock;
import me.hecheng.pay.service.PayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * @author hecheng 2020-05-17 09:41
 * @description
 */
@Controller
@Slf4j
public class PayController {

    @Autowired
    private PayService payService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${url.return}")
    private String returnUrl;

    @Value("${url.notify}")
    private String notifyUrl;

    @Autowired
    private DistributedLock distributedLock;

    /**
     * 支付
     *
     * @param request
     * @param response
     */
    @GetMapping("/pay")
    public void pay(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = request.getSession().getId();
        String lock = distributedLock.lock("ORDER:" + sessionId, 30000L, 5000L);
        if (Objects.nonNull(lock)) {
            try {
                //获得初始化的AlipayClient
                AlipayClient alipayClient = new DefaultAlipayClient(ALiYunConstat.SERVER_URL, ALiYunConstat.APP_ID, ALiYunConstat.APP_PRIVATE_KEY, ALiYunConstat.FORMAT, ALiYunConstat.CHARSET, ALiYunConstat.ALIPAY_PUBLIC_KEY, ALiYunConstat.SIGN_TYPE);
                //创建API对应的request
                AlipayTradePagePayRequest alipayRequest = new AlipayTradePagePayRequest();
                //回调页面
                alipayRequest.setReturnUrl(returnUrl);
                //异步通知页面
                alipayRequest.setNotifyUrl(notifyUrl);
                //请求参数
                JSONObject bizContent = new JSONObject();
                //商户订单号
                String orderId = payService.getOrderId();
                bizContent.put("out_trade_no", orderId);
                //销售产品码，与支付宝签约的产品码名称。注：目前仅支持FAST_INSTANT_TRADE_PAY
                bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
                //订单总金额
                bizContent.put("total_amount", "88.88");
                //订单标题
                bizContent.put("subject", "Iphone6 16G");
                //描述
                bizContent.put("body", "苹果6 16G存储");
                //回传参数
                JSONObject passback_params = new JSONObject();
                passback_params.put("sessionId",sessionId);
                passback_params.put("lock",lock);
                bizContent.put("passback_params", passback_params.toJSONString());
                //业务扩展参数
                JSONObject extend_params = new JSONObject();
                //商户UID
                extend_params.put("sys_service_provider_id", ALiYunConstat.PROVIDER_ID);
                bizContent.put("extend_params", extend_params);
                //请求参数
                alipayRequest.setBizContent(bizContent.toJSONString());
                log.info("请求参数：{}", bizContent.toJSONString());
                String sql = "UPDATE `order` o SET o.request_params = ? WHERE o.order_id = ?";
                jdbcTemplate.update(sql, bizContent.toJSONString(), orderId);
                String form = "";
                try {
                    //调用SDK生成表单
                    form = alipayClient.pageExecute(alipayRequest).getBody();
                } catch (AlipayApiException e) {
                    e.printStackTrace();
                }
                //响应类型
                response.setContentType("text/html;charset=" + ALiYunConstat.CHARSET);
                //直接将完整的表单html输出到页面
                response.getWriter().write(form);
                response.getWriter().flush();
                response.getWriter().close();
            } catch (IOException e) {
                log.error("html输出异常", e);
                throw new RuntimeException(e);
            } catch (Exception e) {
                log.error("生成支付表单异常", e);
                throw new RuntimeException(e);
            }
        } else {
            throw new RuntimeException("创建锁超时");
        }
    }

    /**
     * 异步通知
     *
     * @param request
     * @param response
     */
    @PostMapping("/yb")
    public void yb(HttpServletRequest request, HttpServletResponse response) {
        //获取支付宝POST过来反馈信息
        Map<String, String> paramsMap = new HashMap<>();
        Map requestParams = request.getParameterMap();
        for (Iterator iter = requestParams.keySet().iterator(); iter.hasNext(); ) {
            String name = (String) iter.next();
            String[] values = (String[]) requestParams.get(name);
            String valueStr = "";
            for (int i = 0; i < values.length; i++) {
                valueStr = (i == values.length - 1) ? valueStr + values[i]
                        : valueStr + values[i] + ",";
            }
            paramsMap.put(name, valueStr);
        }
        //商户订单号
        String out_trade_no = request.getParameter("out_trade_no");
        //支付宝交易号
        String trade_no = request.getParameter("trade_no");
        //交易状态
        String trade_status = request.getParameter("trade_status");
        //回传参数
        JSONObject passback_params = JSONObject.parseObject(request.getParameter("passback_params"));
        try {
            //调用SDK验证签名
            boolean verify_result = AlipaySignature.rsaCheckV1(paramsMap, ALiYunConstat.ALIPAY_PUBLIC_KEY, ALiYunConstat.CHARSET, ALiYunConstat.SIGN_TYPE);
            log.info("SDK验证签名：{}", verify_result);
            if (verify_result) {
                // TODO 验签成功后，按照支付结果异步通知中的描述，对支付结果中的业务内容进行二次校验，校验成功后在response中返回success并继续商户自身业务处理，校验失败返回failure
                String sql = "UPDATE `order` o SET o.alipay_id = ?, o.response_params = ? WHERE o.order_id = ?";
                jdbcTemplate.update(sql, trade_no, JSONObject.toJSONString(paramsMap), out_trade_no);
                response.getWriter().write("success");
            } else {
                // TODO 验签失败则记录异常日志，并在response中返回failure.
                response.getWriter().write("failure");
            }
        } catch (AlipayApiException e) {
            log.error("调用SDK验证签名失败", e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            log.error("写出失败", e);
            throw new RuntimeException(e);
        } finally {
            distributedLock.unLock("ORDER:" + passback_params.get("sessionId").toString(), passback_params.get("lock").toString());
        }
    }

    /**
     * 回调
     *
     * @return
     */
    @GetMapping("cg")
    @ResponseBody
    public String cg() {
        return "支付成功";
    }

}
