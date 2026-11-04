package com.shopglobal.mockapi;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "ShopGlobal Marketplace API",
        version = "1.0.0",
        description = "Mock product, order, return-policy and delivery-address APIs for ShopGlobal. "
                + "Data is a static catalogue held in memory; changes are lost on restart."))
public class MockApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockApiApplication.class, args);
    }
}
