import os
from torch.utils.data import Dataset
from PIL import Image
import torchvision.transforms as transforms
from torchvision import transforms

class SegmentationDataset(Dataset):
    """
    用于语义分割的自定义数据集
    - 图片文件夹：frames_dir
    - mask 文件夹：masks_dir
    - mask 像素值：0=不可行, 1=可行
    """
    def __init__(self, frames_dir, masks_dir, transform=None, mask_transform=None):
        super().__init__()
        self.frames_dir = frames_dir
        self.masks_dir = masks_dir
        self.transform = transform
        self.mask_transform = mask_transform

        # 获取所有图片文件名（假设 mask 和图片同名）
        self.images = sorted([f for f in os.listdir(frames_dir) if f.endswith((".jpg", ".png"))])

    def __len__(self):
        return len(self.images)

    def __getitem__(self, idx):
        img_name = self.images[idx]
        img_path = os.path.join(self.frames_dir, img_name)
        mask_path = os.path.join(self.masks_dir, img_name)

        # 读取图片
        image = Image.open(img_path).convert("RGB")
        mask = Image.open(mask_path)

        if self.transform:
            image = self.transform(image)
            # 获取图片变换后的尺寸
            img_size = image.shape[1:]  # (H, W)
            
            # 对 mask 应用相同的 resize
            mask = mask.resize((img_size[1], img_size[0]), resample=Image.NEAREST)
            mask = transforms.ToTensor()(mask).long()
        elif self.mask_transform:
            mask = self.mask_transform(mask)
        else:
            mask = mask.resize((640, 480), resample=Image.NEAREST)
            mask = transforms.ToTensor()(mask).long()

        return image, mask