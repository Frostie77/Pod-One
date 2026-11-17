set(plume_source "${RT64_DIR}/src/contrib/plume/plume_vulkan.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${plume_source}")
file(READ "${plume_source}" plume)

set(old_copy [=[
            vkCmdCopyBufferToImage(vk, srcBuffer->vk, dstTexture->vk, toImageLayout(dstTexture->textureLayout), 1, &imageCopy);
        }
        else {
]=])
set(new_copy [=[
            vkCmdCopyBufferToImage(vk, srcBuffer->vk, dstTexture->vk, toImageLayout(dstTexture->textureLayout), 1, &imageCopy);
        }
        else if ((dstLocation.type == RenderTextureCopyType::PLACED_FOOTPRINT) && (srcLocation.type == RenderTextureCopyType::SUBRESOURCE)) {
            // Pod One reads back a single-sample D32 depth target for clipping diagnosis.
            assert(dstBuffer != nullptr && srcTexture != nullptr);
            assert(srcTexture->desc.format == RenderFormat::D32_FLOAT && srcTexture->desc.multisampling.sampleCount == 1);
            assert(dstX == 0 && dstY == 0 && dstZ == 0 && srcBox == nullptr);
            const auto &footprint = dstLocation.placedFootprint;
            VkBufferImageCopy imageCopy = {};
            imageCopy.bufferOffset = footprint.offset;
            imageCopy.bufferRowLength = footprint.rowWidth;
            imageCopy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT;
            imageCopy.imageSubresource.baseArrayLayer = srcLocation.subresource.arrayIndex;
            imageCopy.imageSubresource.layerCount = 1;
            imageCopy.imageSubresource.mipLevel = srcLocation.subresource.mipLevel;
            imageCopy.imageExtent = { footprint.width, footprint.height, footprint.depth };
            vkCmdCopyImageToBuffer(vk, srcTexture->vk, toImageLayout(srcTexture->textureLayout), dstBuffer->vk, 1, &imageCopy);
        }
        else {
]=])

set(old_mapping [=[
        return data;
    }

    void VulkanBuffer::unmap(uint32_t subresource, const RenderRange *writtenRange) {
        vmaUnmapMemory(device->allocator, allocation);
]=])
set(new_mapping [=[
        if (desc.heapType == RenderHeapType::READBACK) {
            const uint64_t offset = readRange ? readRange->begin : 0;
            const uint64_t size = readRange ? readRange->end - readRange->begin : VK_WHOLE_SIZE;
            res = vmaInvalidateAllocation(device->allocator, allocation, offset, size);
            if (res != VK_SUCCESS) {
                fprintf(stderr, "vmaInvalidateAllocation failed with error code 0x%X.\n", res);
                vmaUnmapMemory(device->allocator, allocation);
                return nullptr;
            }
        }
        return data;
    }

    void VulkanBuffer::unmap(uint32_t subresource, const RenderRange *writtenRange) {
        // VMA skips cache maintenance for host-coherent memory.
        if (desc.heapType == RenderHeapType::UPLOAD || desc.heapType == RenderHeapType::GPU_UPLOAD) {
            const uint64_t offset = writtenRange ? writtenRange->begin : 0;
            const uint64_t size = writtenRange ? writtenRange->end - writtenRange->begin : VK_WHOLE_SIZE;
            const VkResult res = vmaFlushAllocation(device->allocator, allocation, offset, size);
            if (res != VK_SUCCESS) {
                fprintf(stderr, "vmaFlushAllocation failed with error code 0x%X.\n", res);
            }
        }
        vmaUnmapMemory(device->allocator, allocation);
]=])

set(old_attachment_load [=[
            // Upgrade the operations to NONE if supported. Fixes the following validation issue: https://github.com/KhronosGroup/Vulkan-ValidationLayers/issues/2349
            // We prefer to just ignore this potential hazard on older Vulkan versions as it just seems to be an edge case for some hardware.
            const bool preferNoneForReadOnly = desc.depthAttachmentReadOnly && device->loadStoreOpNoneSupported;
            VkAttachmentDescription attachment = {};
            attachment.format = toVk(depthAttachmentViewDesc.format);
            attachment.samples = VkSampleCountFlagBits(depthAttachment->desc.multisampling.sampleCount);
            attachment.loadOp = preferNoneForReadOnly ? VK_ATTACHMENT_LOAD_OP_NONE_EXT : VK_ATTACHMENT_LOAD_OP_LOAD;
]=])
set(new_attachment_load [=[
            // Read-only depth testing uses the contents written by the preceding
            // pass. LOAD_OP_NONE would leave them undefined inside this pass.
            const bool preferNoneForReadOnly = desc.depthAttachmentReadOnly && device->loadStoreOpNoneSupported;
            VkAttachmentDescription attachment = {};
            attachment.format = toVk(depthAttachmentViewDesc.format);
            attachment.samples = VkSampleCountFlagBits(depthAttachment->desc.multisampling.sampleCount);
            attachment.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
]=])

foreach(section copy mapping attachment_load)
    string(FIND "${plume}" "${old_${section}}" match_start)
    if(match_start EQUAL -1)
        message(FATAL_ERROR "Pod One's Vulkan transfer patch needs to be updated")
    endif()
    string(REPLACE "${old_${section}}" "${new_${section}}" plume "${plume}")
endforeach()
podone_patch_rt64_source("${plume_source}" "${plume}" plume)
