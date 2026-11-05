package com.advisory.mcp.weather.model;

/**
 * 地理编码结果。查询词与解析结果分开存：模型常常给「杭州」而实际解析到「杭州市」，
 * 返回里带上解析后的规范名，模型才不会把两地搞混。
 */
public record GeoLocation(String query, String name, String region, String country,
                          double latitude, double longitude) {

    public String displayName() {
        StringBuilder sb = new StringBuilder(name);
        if (region != null && !region.isBlank() && !region.equals(name)) {
            sb.append("（").append(region);
            if (country != null && !country.isBlank()) {
                sb.append("，").append(country);
            }
            sb.append("）");
        } else if (country != null && !country.isBlank()) {
            sb.append("（").append(country).append("）");
        }
        return sb.toString();
    }
}
