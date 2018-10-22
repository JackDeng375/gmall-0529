package com.atguigu.gmall.cart.service.impl;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import com.atguigu.gmall.cart.CartItem;
import com.atguigu.gmall.cart.CartService;
import com.atguigu.gmall.cart.CartVo;
import com.atguigu.gmall.cart.SkuItem;
import com.atguigu.gmall.cart.constant.CartConstant;
import com.atguigu.gmall.manager.SkuService;
import com.atguigu.gmall.manager.sku.SkuInfo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.UUID;

@Service
public class CartServiceImpl implements CartService {

    @Autowired
    JedisPool jedisPool;

    @Reference
    SkuService skuService;

    // temp:cart:dasdjaskljljdalj

    @Override
    public String addToCartUnLogin(Integer skuId, String cartKey, Integer num) {
        Jedis jedis = jedisPool.getResource();

        //cart-key : djskaljdaskljdasdkja
        if(!StringUtils.isEmpty(cartKey)){
            //之前创建过购物车
            Boolean exists = jedis.exists(cartKey);
            if(exists == false){
                //传来的购物车这个键不存在；也要新建购物车；假设传来的是非法的 cart-key:ddddddd
                String newCartKey = createCart(skuId, num,false,null);
                return newCartKey;
            }else{
                String skuInfoJson = jedis.hget(cartKey, skuId + "");
                if(!StringUtils.isEmpty(skuInfoJson)){
                    //1、购物车中有此商品，叠加数量
                    CartItem cartItem = JSON.parseObject(skuInfoJson, CartItem.class);
                    cartItem.setNum(cartItem.getNum()+num);
                    //价格重新算一下
                    cartItem.setTotalPrice(cartItem.getTotalPrice());
                    String toJSONString = JSON.toJSONString(cartItem);
                    jedis.hset(cartKey,skuId + "",toJSONString);

                }else {
                    //2、购物车中无此商品，新增商品
                    try {
                        CartItem cartItem = new CartItem();
                        SkuInfo skuInfo = skuService.getSkuInfoBySkuId(skuId);
                        SkuItem skuItem = new SkuItem();
                        BeanUtils.copyProperties(skuInfo,skuItem);
                        cartItem.setNum(num);
                        cartItem.setSkuItem(skuItem);
                        cartItem.setTotalPrice(cartItem.getTotalPrice());

                        String jsonString = JSON.toJSONString(cartItem);
                        jedis.hset(cartKey,skuItem.getId()+"",jsonString);
                    } catch (InterruptedException e) {
                    }
                }
            }

        }else {
           //无购物车就新建
           return createCart(skuId,num,false,null);
        }

        jedis.close();
        //返回之前的cart-key
        return cartKey;
    }

    @Override
    public void addToCartLogin(Integer skuId, Integer userId, Integer num) {
        //登陆后加购物车
        Jedis jedis = jedisPool.getResource();
        Boolean exists = jedis.exists(CartConstant.USER_CART_PREFIX+userId);
        if(exists){
            //用户这个购物车有
            String cartKey = CartConstant.USER_CART_PREFIX+userId;

            String hget = jedis.hget(cartKey, skuId + "");
            if(!StringUtils.isEmpty(hget)){
                //1、有这个商品，叠加数量
                CartItem cartItem = JSON.parseObject(hget, CartItem.class);
                cartItem.setNum(cartItem.getNum()+num);
                cartItem.setTotalPrice(cartItem.getTotalPrice());

                String s = JSON.toJSONString(cartItem);
                jedis.hset(cartKey,skuId+"",s);
            }else {
                try {
                    //2、没这个商品，新增商品
                    CartItem cartItem = new CartItem();
                    //2.1）、查出这个商品
                    SkuInfo skuInfoBySkuId = skuService.getSkuInfoBySkuId(skuId);
                    SkuItem skuItem = new SkuItem();
                    BeanUtils.copyProperties(skuInfoBySkuId,skuItem);
                    //2.2）、设置当前购物车项
                    cartItem.setSkuItem(skuItem);
                    //2.3）、设置商品数量
                    cartItem.setNum(num);
                    //2.4）、更新总价计算
                    cartItem.setTotalPrice(cartItem.getTotalPrice());

                    String json = JSON.toJSONString(cartItem);
                    Long hset = jedis.hset(cartKey, skuId + "", json);
                } catch (InterruptedException e) {
                }
            }

        }else {
            //用户还没有这个购物车
            String newCartKey = createCart(skuId, num, true, userId);
            //新建购物车加商品
        }

    }

    @Override
    public CartVo getYourCart(String cartKey) {
        return null;
    }

    @Override
    public void mergeCart(String cartKey, Integer userId) {

    }


    /**
     * 
     * @param skuId  商品id
     * @param num    数量
     * @param login  是否登陆，是true
     * @param userId  如果登陆了必须传入userId
     * @return
     */
    private String createCart(Integer skuId, Integer num,boolean login,Integer userId){
        Jedis jedis = jedisPool.getResource();
        String newCartKey;
        //新建购物车
        if(login){
            //已登录用的key
            newCartKey = CartConstant.USER_CART_PREFIX+ userId;
        }else {
            //未登陆用的key
            newCartKey = CartConstant.TEMP_CART_PREFIX + UUID.randomUUID().toString().substring(0, 10).replaceAll("-", "");
        }
       

        //保存购物车数据；
        try {
            //1、查出商品的详细信息
            SkuInfo skuInfo = skuService.getSkuInfoBySkuId(skuId);
            //redis用什么样的方式存储比较合适?
            //key -value
            //newCartKey - [{},{},{}]
            //修改2商品的数量 [{},{2},{}]
            //hash
            // newCartKey  field value
            // temp:cart:001  1 {id:1,skuName:ddd,num:xxx};
            // temp:cart:001  3 {id:3,skuName:ddd,num:xxx};
            //问题？购物车数据是有序的？
            // temp:cart:001  skuIdOrder [1,2,3]
            //
            CartItem cartItem = new CartItem();
            SkuItem skuItem = new SkuItem();
            //2、拷贝商品的详细信息进来，准备保存到redis
            BeanUtils.copyProperties(skuInfo,skuItem);
            cartItem.setSkuItem(skuItem);
            cartItem.setNum(num);
            cartItem.setTotalPrice(cartItem.getTotalPrice());

            String jsonString = JSON.toJSONString(cartItem);


            Long hset = jedis.hset(newCartKey, skuItem.getId()+"", jsonString);


        } catch (InterruptedException e) {
        }


        jedis.close();
        return  newCartKey;
    }
}
