package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list; //？表示泛型，以后可以存任意类型数据
    private Long minTime;
    private Integer offset;
}
