package com.advisory.mcp.weather.support;

/**
 * 城市名无法解析。工具层会把这条消息原样（或稍作包装）返回给模型，
 * 所以文案要能被模型直接拿去反问用户，而不是一段堆栈。
 */
public class CityNotFoundException extends RuntimeException {

    private final String city;

    public CityNotFoundException(String city) {
        super("未找到城市：" + city);
        this.city = city;
    }

    public String city() {
        return city;
    }
}
