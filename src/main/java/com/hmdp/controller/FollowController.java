package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService followService;
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id")Long followUserId,@PathVariable("isFollow")Boolean isFollow){
        return followService.follow(followUserId,isFollow);
    }
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId){
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id")Long id){
        return followService.followCommons(id);
    }

    /**
     * 查我的粉丝列表（谁关注了我）
     */
    @GetMapping("/fans")
    public Result queryFans(@RequestParam(value = "current", defaultValue = "1") Integer current){
        return followService.queryFans(current);
    }

    /**
     * 查我关注的人
     */
    @GetMapping("/followees")
    public Result queryFollowees(@RequestParam(value = "current", defaultValue = "1") Integer current){
        return followService.queryFollowees(current);
    }
}
