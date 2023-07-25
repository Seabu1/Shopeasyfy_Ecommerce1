package com.shopme.checkout;

import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopme.ControllerHelper;
import com.shopme.Utility;
import com.shopme.address.AddressService;
import com.shopme.checkout.paypal.PayPalApiException;
import com.shopme.checkout.paypal.PayPalService;
import com.shopme.common.entity.Address;
import com.shopme.common.entity.CartItem;
import com.shopme.common.entity.Customer;
import com.shopme.common.entity.ShippingRate;
import com.shopme.common.entity.order.Order;
import com.shopme.common.entity.order.PaymentMethod;
import com.shopme.order.OrderService;
import com.shopme.setting.CurrencySettingBag;
import com.shopme.setting.EmailSettingBag;
import com.shopme.setting.PaymentSettingBag;
import com.shopme.setting.SettingService;
import com.shopme.shipping.ShippingRateService;
import com.shopme.shoppingcart.ShoppingCartService;
import org.springframework.http.*;

@Controller
public class CheckoutController {

	@Autowired private CheckoutService checkoutService;
	@Autowired private ControllerHelper controllerHelper;
	@Autowired private AddressService addressService;
	@Autowired private ShippingRateService shipService;
	@Autowired private ShoppingCartService cartService;
	@Autowired private OrderService orderService;
	@Autowired private SettingService settingService;
	@Autowired private PayPalService paypalService;
	
	@Autowired
	private JavaMailSender mailSender;
	
    private HttpHeaders headers;

	
	@PostMapping("/checkout")
	public String showCheckoutPage(Model model, HttpServletRequest request) {
		
		System.out.println("fdsfhjdsfjdsfhjshdjfhsdjfhdsjf");

		Customer customer = controllerHelper.getAuthenticatedCustomer(request);
		
		Address defaultAddress = addressService.getDefaultAddress(customer);
		ShippingRate shippingRate = null;
		
		if (defaultAddress != null) {
			model.addAttribute("shippingAddress", defaultAddress.toString());
			shippingRate = shipService.getShippingRateForAddress(defaultAddress);
		} else {
			model.addAttribute("shippingAddress", customer.toString());
			shippingRate = shipService.getShippingRateForCustomer(customer);
		}
		
		if (shippingRate == null) {
			return "redirect:/cart";
		}
		
		List<CartItem> cartItems = cartService.listCartItems(customer);
		//System.out.println(cartItems.size());
		CheckoutInfo checkoutInfo = checkoutService.prepareCheckout(cartItems, shippingRate);
		
		String currencyCode = settingService.getCurrencyCode();
		PaymentSettingBag paymentSettings = settingService.getPaymentSettings();
		String paypalClientId = paymentSettings.getClientID();
		
		System.out.println("ruewoiruiewurioewurewuriewurewi");

		
		model.addAttribute("paypalClientId", paypalClientId);
		model.addAttribute("currencyCode", currencyCode);
		model.addAttribute("customer", customer);
		model.addAttribute("checkoutInfo", checkoutInfo);
		model.addAttribute("cartItems", cartItems);
		
		return "checkout/checkout";
	}
	
	@PostMapping("/place_order")
	public String placeOrder(HttpServletRequest request) 
			throws UnsupportedEncodingException, MessagingException {
		String paymentType = request.getParameter("paymentMethod");
		System.out.println("======================================");
		
		System.out.println(paymentType);
		System.out.println("======================================");

		PaymentMethod paymentMethod = PaymentMethod.valueOf(paymentType);
	
		
		Customer customer = controllerHelper.getAuthenticatedCustomer(request);
		
		Address defaultAddress = addressService.getDefaultAddress(customer);
		ShippingRate shippingRate = null;
		
		if (defaultAddress != null) {
			shippingRate = shipService.getShippingRateForAddress(defaultAddress);
		} else {
			shippingRate = shipService.getShippingRateForCustomer(customer);
		}
				
		List<CartItem> cartItems = cartService.listCartItems(customer);
		CheckoutInfo checkoutInfo = checkoutService.prepareCheckout(cartItems, shippingRate);
		
		Order createdOrder = orderService.createOrder(customer, defaultAddress, cartItems, paymentMethod, checkoutInfo);
		cartService.deleteByCustomer(customer);
		sendOrderConfirmationEmail(request, createdOrder);
		return "checkout/order_completed";
	}
	@GetMapping("/place_order")
	public String placeOrderWithKhalti(HttpServletRequest request) 
			throws UnsupportedEncodingException, MessagingException {
		String paymentType = "khalti";
		PaymentMethod paymentMethod = PaymentMethod.valueOf(paymentType);
	     System.out.println("=========================================");
	     System.out.println(paymentType);
	     System.out.println("=========================================");

		Customer customer = controllerHelper.getAuthenticatedCustomer(request);
		
		Address defaultAddress = addressService.getDefaultAddress(customer);
		ShippingRate shippingRate = null;
		
		if (defaultAddress != null) {
			shippingRate = shipService.getShippingRateForAddress(defaultAddress);
		} else {
			shippingRate = shipService.getShippingRateForCustomer(customer);
		}
				
		List<CartItem> cartItems = cartService.listCartItems(customer);
		CheckoutInfo checkoutInfo = checkoutService.prepareCheckout(cartItems, shippingRate);
		
		Order createdOrder = orderService.createOrder(customer, defaultAddress, cartItems, paymentMethod, checkoutInfo);
		cartService.deleteByCustomer(customer);
		sendOrderConfirmationEmail(request, createdOrder);
		return "checkout/order_completed";
	}

	private void sendOrderConfirmationEmail(HttpServletRequest request, Order order) 
			throws UnsupportedEncodingException, MessagingException {
		
		
		String toAddress = order.getCustomer().getEmail();
		
		
		
		
		DateFormat dateFormatter =  new SimpleDateFormat("HH:mm:ss E, dd MMM yyyy");
		String orderTime = dateFormatter.format(order.getOrderTime());
		
		CurrencySettingBag currencySettings = settingService.getCurrencySettings();
		String totalAmount = Utility.formatCurrency(order.getTotal(), currencySettings);
		
		String name= order.getCustomer().getFullName();
		String id= String.valueOf(order.getId());
		String time= orderTime;
		String address= order.getShippingAddress();
		String amount= totalAmount;
		
		
		SimpleMailMessage message = new SimpleMailMessage();
		message.setTo(toAddress);
		message.setSubject("Order Request");
		message.setText("Dear "+name+", has done a order request\n"
				       + "amount rs :"+amount+"\n"
				       + "time :"+time+"\n"
				       + "address :"+address+".");
	
		mailSender.send(message);
				
	}
	
	 @GetMapping("/verifypayment")
	    public String verifyPayment(@RequestParam("token") String token,
	            @RequestParam("amount") String amount)
	            throws JsonProcessingException {
	        Map<String, String> details = new HashMap<>();
	        details.put("token", token);
	        details.put("amount", amount);
	        boolean result = verifyPayment(details);
	        System.out.println(result);
	        return "redirect:/place_order";
	    }
	 
	    @CrossOrigin
	    public boolean verifyPayment(Map<String, String> paymentDetails) throws JsonProcessingException {

	        final String uri = "https://khalti.com/api/v2/payment/verify/";
	        RestTemplate restTemplate = new RestTemplate();
	        headers = new HttpHeaders();
	        headers.set("Authorization", "Key test_secret_key_1bc9f938dc3e46369f019527b8acb15b");

	        String json = new ObjectMapper().writeValueAsString(paymentDetails);

	        headers.setContentType(MediaType.APPLICATION_JSON);
	        HttpEntity<String> entity = new HttpEntity<>(json, headers);
	        ResponseEntity resp = restTemplate.exchange(uri, HttpMethod.POST, entity, String.class);
	        int status = resp.getStatusCodeValue();
	        System.out.println(resp.getBody());
	        if(status==200) {
//	        	saveInvestmentdata(token,amount);
	            return true;
	        }
	        return false;
	    }

}