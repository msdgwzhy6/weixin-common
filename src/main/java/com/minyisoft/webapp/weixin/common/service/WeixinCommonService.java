package com.minyisoft.webapp.weixin.common.service;

import java.text.MessageFormat;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

import redis.clients.jedis.Jedis;

import com.google.common.base.Optional;
import com.minyisoft.webapp.core.exception.ServiceException;
import com.minyisoft.webapp.core.security.utils.DigestUtils;
import com.minyisoft.webapp.core.security.utils.EncodeUtils;
import com.minyisoft.webapp.core.utils.mapper.json.JsonMapper;
import com.minyisoft.webapp.core.utils.redis.JedisTemplate;
import com.minyisoft.webapp.core.utils.redis.JedisTemplate.JedisActionNoResult;

@Service
public class WeixinCommonService {
	private Logger logger = LoggerFactory.getLogger(getClass());

	@Value("${weixin.appID}")
	private String weixinAppID;
	@Value("${weixin.appsecret}")
	private String weixinAppSecret;
	@Value("${weixin.access_token_url}")
	private String accessTokenUrl;
	@Value("${weixin.oauth2.access_token_url}")
	private String oauth2AccessTokenUrl;
	@Autowired
	private RestTemplate restTemplate;
	@Autowired
	private JedisTemplate jedisTemplate;

	private static final String WEIXIN_ACCESS_TOKEN_KEY = "weixin:access_token";// 微信access_token在redis的键值

	/**
	 * 获取微信access_token
	 * 
	 * @return
	 */
	public String getAccessToken() {
		String accessToken = null;
		if (StringUtils.isNotBlank(accessToken = jedisTemplate.get(WEIXIN_ACCESS_TOKEN_KEY))) {
			return accessToken;
		}
		Map<String, String> resultMap = _queryFromWeixinServer(MessageFormat.format(accessTokenUrl, weixinAppID,
				weixinAppSecret));
		if (resultMap.containsKey("access_token")) {
			// 在redis中缓存access_token，过期时间比微信官方时间短1分钟
			jedisTemplate.setex(WEIXIN_ACCESS_TOKEN_KEY, resultMap.get("access_token"),
					Integer.parseInt(resultMap.get("expires_in")) - 60);
			return resultMap.get("access_token");
		} else {
			logger.error(MessageFormat.format("获取微信access_token失败，错误码：{0}，错误提示：{1}", resultMap.get("errcode"),
					resultMap.get("errmsg")));
		}
		throw new ServiceException("无法获取微信access_token");
	}

	/**
	 * 在网页授权的情况下，通过code获取微信用户对应本服务号的openId
	 * 
	 * @param weixinOAuthCode
	 * @return
	 */
	public Optional<String> getOpenIdByOAuthCode(String weixinOAuthCode) {
		if (StringUtils.isBlank(weixinOAuthCode)) {
			return Optional.absent();
		}
		Map<String, String> resultMap = _queryFromWeixinServer(MessageFormat.format(oauth2AccessTokenUrl, weixinAppID,
				weixinAppSecret, weixinOAuthCode));
		if (resultMap.containsKey("openid")) {
			return Optional.of(resultMap.get("openid"));
		} else {
			logger.error(MessageFormat.format("通过网页授权code获取微信openId失败，错误码：{0}，错误提示：{1}", resultMap.get("errcode"),
					resultMap.get("errmsg")));
		}
		return Optional.absent();
	}

	/**
	 * 查询微信指定url获取结果
	 * 
	 * @param url
	 * @return
	 */
	private Map<String, String> _queryFromWeixinServer(String url) {
		ResponseEntity<String> result = restTemplate.getForEntity(url, String.class);
		return JsonMapper.NON_EMPTY_MAPPER.fromJson(result.getBody(), JsonMapper.NON_EMPTY_MAPPER.getMapper()
				.getTypeFactory().constructMapType(Map.class, String.class, String.class));
	}

	private static final String WEIXIN_TICKET_KEY_PREFIX = "weixin:ticket:";// 微信ticket在redis的键值前缀

	/**
	 * 根据微信用户openId生成系统票据，票据用于附加到超链接中，方便绑定了账号的微信用户免手工登录系统，同时票据有失效时间，
	 * 避免超链接通过微信分享时暴露分享者的个人信息
	 * 
	 * @param weixinOpenId
	 * @return
	 */
	public String genWeixinTicket(final String weixinOpenId, final int ticketExpiredSeconds) {
		Assert.hasLength(weixinOpenId, "待生成ticket的目标微信号不能为空");
		final String ticket = EncodeUtils.encodeHex(DigestUtils.generateSalt(6));
		jedisTemplate.set(WEIXIN_TICKET_KEY_PREFIX + ticket, weixinOpenId);
		// 若设置的失效秒数大于0，设置ticket失效秒数
		if (ticketExpiredSeconds > 0) {
			jedisTemplate.execute(new JedisActionNoResult() {
				@Override
				public void action(Jedis jedis) throws Exception {
					jedis.expire(WEIXIN_TICKET_KEY_PREFIX + ticket, ticketExpiredSeconds);
				}
			});
		}
		return ticket;
	}
	
	public String genWeixinTicket(final String weixinOpenId) {
		return genWeixinTicket(weixinOpenId, 0);
	}

	/**
	 * 根据微信ticket获取微信用户openId，ticket获取一次后马上失效
	 * 
	 * @param weixinTicket
	 * @return
	 */
	public Optional<String> getOpenIdByTicket(String weixinTicket) {
		if (StringUtils.isNotBlank(weixinTicket)) {
			String weixinOpenId = jedisTemplate.get(WEIXIN_TICKET_KEY_PREFIX + weixinTicket);
			if (StringUtils.isNotBlank(weixinOpenId)) {
				jedisTemplate.del(WEIXIN_TICKET_KEY_PREFIX + weixinTicket);
				return Optional.of(weixinOpenId);
			}
		}
		return Optional.absent();
	}
}