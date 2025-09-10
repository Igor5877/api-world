import enum
from sqlalchemy import (
    Column,
    Integer,
    String,
    DateTime,
    Enum as SQLAlchemyEnum,
    ForeignKey,
    UniqueConstraint,
)
from sqlalchemy.orm import relationship, backref
from sqlalchemy.sql import func
from app.db.base_class import Base
import uuid

class RoleEnum(str, enum.Enum):
    owner = "owner"
    moderator = "moderator"
    member = "member"

class Team(Base):
    __tablename__ = "teams"

    id = Column(Integer, primary_key=True, autoincrement=True)
    name = Column(String(255), unique=True, nullable=False, index=True)
    
    # The UUID of the player who created and owns the team.
    owner_uuid = Column(String(36), nullable=False, index=True)
    
    # The island associated with this team. This is a one-to-one relationship.
    # The 'team' attribute on the Island model provides the back-reference.
    island = relationship("Island", back_populates="team", uselist=False, cascade="all, delete-orphan")

    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())

    # Relationships
    # One-to-many relationship with TeamMember
    members = relationship("TeamMember", back_populates="team", cascade="all, delete-orphan")

class TeamMember(Base):
    __tablename__ = "team_members"

    id = Column(Integer, primary_key=True, autoincrement=True)
    
    team_id = Column(Integer, ForeignKey("teams.id", ondelete="CASCADE"), nullable=False, index=True)
    player_uuid = Column(String(36), nullable=False, index=True)
    
    role = Column(
        SQLAlchemyEnum(RoleEnum, name="role_enum", create_constraint=True, validate_strings=True),
        nullable=False,
        default=RoleEnum.member,
    )
    
    joined_at = Column(DateTime, server_default=func.now())

    # Relationships
    team = relationship("Team", back_populates="members")

    # Constraints
    __table_args__ = (
        UniqueConstraint("team_id", "player_uuid", name="uq_team_player"),
    )
