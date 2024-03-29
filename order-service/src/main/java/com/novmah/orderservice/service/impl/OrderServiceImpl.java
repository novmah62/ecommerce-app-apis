package com.novmah.orderservice.service.impl;

import com.novmah.basedomains.constant.AppConstant;
import com.novmah.basedomains.dto.*;
import com.novmah.basedomains.event.OrderEvent;
import com.novmah.basedomains.status.OrderItemStatus;
import com.novmah.basedomains.status.Status;
import com.novmah.orderservice.domain.Order;
import com.novmah.orderservice.domain.OrderItem;
import com.novmah.orderservice.exception.ResourceNotFoundException;
import com.novmah.orderservice.mapper.OrderItemMapper;
import com.novmah.orderservice.mapper.OrderMapper;
import com.novmah.orderservice.producer.OrderProducer;
import com.novmah.orderservice.repository.OrderRepository;
import com.novmah.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper mapper;
    private final OrderItemMapper itemMapper;
    private final OrderProducer orderProducer;
    private final WebClient webClient;

    @Override
    public String saveByCart(OrderDto orderDto) {
        Order order = mapper.map(orderDto);
        List<OrderItem> orderItems = new ArrayList<>();
        Optional.ofNullable(callCartService(orderDto.getBuyerDto().getId())).ifPresent(cartItemDtoList -> {
            BigDecimal totalAmount = new BigDecimal("0.00");
            for (CartItemDto ci : callCartService(orderDto.getBuyerDto().getId())) {
                OrderItem oi = OrderItem.builder()
                        .quantity(ci.getQuantity())
                        .reviewStatus(Status.PENDING)
                        .orderStatus(OrderItemStatus.ORDERED)
                        .productId(ci.getProductDto().getId())
                        .order(order).build();

                ProductDto productDto = callProductService(oi.getProductId());
                if (oi.getQuantity() > productDto.getQuantity())
                    throw new ResourceNotFoundException("Product is not in stock, please try again later");
                orderItems.add(oi);
                totalAmount = totalAmount.add(ci.getProductDto().getPrice()
                        .multiply(new BigDecimal(ci.getQuantity())));
            }
            order.setTotalAmount(totalAmount);
            order.setPaymentId(UUID.randomUUID().toString());
            order.setOrderItems(orderItems);
        });
        Order orderS = orderRepository.save(order);
        OrderDto orderDtoS = mapper.map(orderS);
        orderDtoS.setOrderItemDtoList(orderItems.stream().map(itemMapper::map).toList()); // ?
        orderDtoS.setPaymentDto(PaymentDto.builder()
                .id(orderS.getPaymentId())
                .orderId(orderS.getId())
                .isPayed(orderDto.getPaymentDto().getIsPayed())
                .status(orderDto.getPaymentDto().getStatus()).build());
        // remote cart item and save payment
        orderProducer.sendMessage(OrderEvent.builder()
                .orderDto(orderDtoS).build());
        return "Order saved successfully";
    }

    @Override
    public OrderDto findById(Integer id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "order ID", id));
        return mapper.map(order, callBuyerService(order.getBuyerId()),
                                 callAddressService(order.getShippingAddressId()),
                                 callAddressService(order.getBillingAddressId()),
                                 callPaymentService(id));
    }

    @Override
    public List<OrderDto> findAll() {
        List<Order> orders = orderRepository.findAll();
        return orders.stream()
                .map(mapper::map)
                .peek(f -> {
                    f.setBuyerDto(callBuyerService(f.getBuyerDto().getId()));
                    f.setShippingAddressDto(callAddressService(f.getShippingAddressDto().getId()));
                    f.setBillingAddressDto(callAddressService(f.getBillingAddressDto().getId()));
                    f.setPaymentDto(callPaymentService(f.getPaymentDto().getOrderId()));
                })
                .toList();
    }

    @Override
    public OrderDto update(OrderDto orderDto) {
        if (!orderRepository.existsById(orderDto.getId()))
            throw new ResourceNotFoundException("Order", "order ID", orderDto.getId());
        Order order = orderRepository.save(mapper.map(orderDto));
        return mapper.map(order, callBuyerService(order.getBuyerId()),
                callAddressService(order.getShippingAddressId()),
                callAddressService(order.getBillingAddressId()),
                callPaymentService(order.getId()));
    }

    @Override
    public String delete(Integer id) {
        orderRepository.deleteById(id);
        return "Order deleted successfully";
    }

    public BuyerDto callBuyerService(Integer buyerId) {
        try {
            return webClient.get()
                    .uri(AppConstant.BUYER_SERVICE_URL + buyerId)
                    .retrieve()
                    .bodyToMono(BuyerDto.class)
                    .block();
        } catch (RuntimeException e) {
            throw new ResourceNotFoundException("Error calling buyer service: " + e);
        }
    }

    public AddressDto callAddressService(Integer addressId) {
        try {
            return webClient.get()
                    .uri(AppConstant.ADDRESS_SERVICE_URL + addressId)
                    .retrieve()
                    .bodyToMono(AddressDto.class)
                    .block();
        } catch (RuntimeException e) {
            throw new ResourceNotFoundException("Error calling address service: " + e);
        }
    }

    public ProductDto callProductService(Integer productId) {
        try {
            return webClient.get()
                    .uri(AppConstant.PRODUCT_SERVICE_URL + productId)
                    .retrieve()
                    .bodyToMono(ProductDto.class)
                    .block();
        } catch (RuntimeException e) {
            throw new ResourceNotFoundException("Error calling product service: " + e);
        }
    }

    public PaymentDto callPaymentService(Integer orderId) {
        try {
            return webClient.get()
                    .uri(AppConstant.PAYMENT_SERVICE_URL + "order/" + orderId)
                    .retrieve()
                    .bodyToMono(PaymentDto.class)
                    .block();
        } catch (RuntimeException e) {
            throw new ResourceNotFoundException("Error calling payment service: " + e);
        }
    }

    public List<CartItemDto> callCartService(Integer buyerId) {
        try {
            return webClient.get()
                    .uri(AppConstant.CART_SERVICE_URL + "buyer/" + buyerId)
                    .retrieve()
                    .bodyToFlux(CartItemDto.class)
                    .collectList()
                    .block();
        } catch (RuntimeException e) {
            throw new ResourceNotFoundException("Error calling cart service: " + e);
        }
    }

}
